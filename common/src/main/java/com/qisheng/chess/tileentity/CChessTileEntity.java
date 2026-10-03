package com.qisheng.chess.tileentity;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 中国象棋棋盘方块的 TileEntity —— 对局的**持久化锚点**。
 *
 * <p>对局本体仍然活在 {@link SessionManager} 的进程内单例里(那里才有并发
 * 语义、玩家索引与广播路径),这里只负责在存档里留一份快照:
 *
 * <ul>
 *   <li>{@link #saveAdditional} 把当前会话写进自己的 NBT;</li>
 *   <li>{@link #load} 只**反序列化**到内存字段,不碰 SessionManager;</li>
 *   <li>{@link #ensureSession} 在有人真正使用棋盘时把快照"认领"进
 *       SessionManager(若已经有更新的内存会话则丢弃快照)。</li>
 * </ul>
 *
 * <p>为什么不在 {@code load()} 里直接注册会话:BlockEntity 的 {@code load()}
 * 在**客户端**也会跑(同步区块时),而且区块卸载重载会再次调用它 —— 那样会把
 * 内存里更新的对局用旧快照覆盖掉,还会在客户端凭空建出一堆全局会话。
 *
 * <p>0.1.1 及以前这里什么都不存,后果是**服务器一重启,棋盘就废了**:会话没了,
 * 而 {@code onPlace} 只在方块被放置时触发,重新进服右键只会得到"该棋盘无效"。
 */
public class CChessTileEntity extends BlockEntity {

    private static final String TAG_SESSION = "Session";

    /** 从存档读出来、但还没被认领的会话(仅服务端会用到)。 */
    private GameSession restored = null;

    public CChessTileEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CCHESS.get(), pos, state);
    }

    /**
     * 取得本棋盘的对局,**必要时从存档恢复**;没有存档就新建一个。
     *
     * <p>只应在服务端调用。客户端没有对局状态(GUI 的数据全部来自 S2C 包)。
     *
     * @return 该棋盘的会话;仅当自身不在服务端时返回 {@code null}
     */
    public GameSession ensureSession() {
        return ensureSession(null);
    }

    /**
     * 取得本棋盘的对局,并在新建时按 {@code preferredVariantId} 打上棋种标签
     * (v0.4 起,棋盘方块决定棋种;xiangqi / international / gomoku / go9 /
     * go19)。已有会话或存档恢复时,棋种由存档中的 {@code Variant} 字段决定,
     * 这个参数仅作"新建"路径的提示 —— 避免新放置的围棋方块意外开一局象棋。
     */
    public GameSession ensureSession(String preferredVariantId) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        BoardKey key = BoardKey.of(serverLevel, worldPosition);
        SessionManager sm = SessionManager.get();

        GameSession live = sm.get(key);
        if (live != null) {
            // Apply the block's preferred variant to a still-default live session.
            // This happens when {@link AbstractChessBoardBlock#onPlace} has already
            // created the session with DEFAULT_ID via SessionManager.getOrCreate
            // (before the block's variant ID was known to the session), and a
            // first-time right-click arrives shortly after. Without this branch
            // a freshly-placed gomoku/go9/go19 board would silently stay as
            // xiangqi — because the live check at the top short-circuits before
            // the preferred-variant logic runs further down.
            if (preferredVariantId != null && !preferredVariantId.isEmpty()
                    && BoardRegistry.DEFAULT_ID.equals(live.getVariantId())) {
                live.setVariantId(preferredVariantId);
            }
            restored = null;
            return live;
        }
        if (restored != null) {
            GameSession adopted = sm.adopt(key, restored);
            restored = null;
            return adopted;
        }
        GameSession fresh = sm.getOrCreate(key);
        if (preferredVariantId != null && !preferredVariantId.isEmpty()
                && BoardRegistry.DEFAULT_ID.equals(fresh.getVariantId())) {
            fresh.setVariantId(preferredVariantId);
        }
        return fresh;
    }

    /**
     * 本棋盘当前**在内存里**的对局,不触发恢复、不新建。
     * 供渲染/查询路径使用 —— 它们不该有副作用。
     */
    public GameSession peekSession() {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return SessionManager.get().get(BoardKey.of(serverLevel, worldPosition));
    }

    /** 兼容旧调用点:{@link #peekSession()}。 */
    public GameSession getSession() {
        return peekSession();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.restored = tag.contains(TAG_SESSION, Tag.TAG_COMPOUND)
                ? GameSession.fromTag(tag.getCompound(TAG_SESSION))
                : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        GameSession session = peekSession();
        // Not adopted yet (nobody has touched this board since the restart):
        // write the snapshot straight back, otherwise an unrelated chunk save
        // would silently erase the stored game.
        if (session == null) session = restored;
        if (session != null) {
            tag.put(TAG_SESSION, session.save());
        }
    }

    /**
     * Do not ship the (potentially large) game snapshot to clients in the block
     * entity update tag. The board GUI receives everything it needs through the
     * S2C sync packets instead.
     */
    @Override
    public CompoundTag getUpdateTag() {
        return new CompoundTag();
    }

    /**
     * Mark the owning chunk so it will be written on the next save.
     *
     * <p>Called after every broadcast of a new position, which is the only time
     * the persisted state actually changes.
     */
    public static void markChanged(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        if (level.getBlockEntity(pos) instanceof CChessTileEntity be) {
            be.setChanged();
        }
    }
}
