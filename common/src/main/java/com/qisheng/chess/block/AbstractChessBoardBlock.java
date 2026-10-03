package com.qisheng.chess.block;

import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.pvp.BoardKey;
import com.qisheng.chess.pvp.BoardMode;
import com.qisheng.chess.pvp.GameBroadcaster;
import com.qisheng.chess.pvp.GameMessages;
import com.qisheng.chess.pvp.GameResult;
import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.GameState;
import com.qisheng.chess.pvp.PvcController;
import com.qisheng.chess.pvp.SessionManager;
import com.qisheng.chess.tileentity.CChessTileEntity;
import com.qisheng.chess.util.CChessUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Base class for every "chess-like board" block the mod ships.
 *
 * <p>v0.4 introduces two more concrete blocks —
 * {@link GomokuBoardBlock} and {@link GoBoardBlock} — so the joint logic
 * (right-click flow, proximity check, FACING, session key) moves up here
 * and the subclasses only declare the variant they play and the visuals
 * (map color, sound) that distinguish them.
 *
 * <p>All blocks use the same
 * {@link com.qisheng.chess.tileentity.CChessTileEntity} family — there is
 * a single {@code BlockEntityType} registered with every concrete block
 * so the chunk's tile entity storage doesn't fragment by game type.
 */
@SuppressWarnings("deprecation")
public abstract class AbstractChessBoardBlock extends BaseEntityBlock {

    /**
     * Which horizontal direction the board's "front" faces. Determines the
     * default piece orientation: when {@link Direction#SOUTH} the GUI renders
     * flipped 180° (red at the top, black at the bottom) so a board mounted
     * against a north wall looks correct to viewers standing on its south side.
     */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected AbstractChessBoardBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    /**
     * The variant id this block creates new sessions with. Subclasses pin
     * to {@code "xiangqi"}, {@code "international"}, {@code "gomoku"},
     * {@code "go9"} or {@code "go19"}.
     *
     * <p>For blocks whose variant depends on the live {@link BlockState}
     * (e.g. {@link GoBoardBlock}'s {@code size}), override
     * {@link #getVariantId(BlockState)} instead of this method.
     */
    public abstract String getVariantId();

    /**
     * State-aware variant id. Defaults to {@link #getVariantId()}; the
     * Go board overrides this so its {@code size} property chooses between
     * {@code "go9"} and {@code "go19"}.
     */
    public String getVariantId(BlockState state) {
        return getVariantId();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Place facing the placer so the "front" of the board is the side they
        // walked up to. Falling back to NORTH for non-player placements.
        Direction face = ctx.getHorizontalDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, face);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CChessTileEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (level instanceof ServerLevel serverLevel && !level.isClientSide()) {
            SessionManager.get().getOrCreate(serverLevel, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean isMoving) {
        // BlockState changes (e.g. a neighbouring block update rewriting this
        // position) also fire onRemove. Only drop the session when the board is
        // actually gone, otherwise a plain state refresh would silently end a
        // running game.
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            SessionManager.get().remove(BoardKey.of(level, pos));
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                  Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.sidedSuccess(level.isClientSide());
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        SessionManager sm = SessionManager.get();
        if (!sm.isPlayerNear(serverLevel, serverPlayer.getUUID(), pos)) {
            serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.board.too_far"));
            return InteractionResult.FAIL;
        }

        BoardKey key = BoardKey.of(serverLevel, pos);
        CChessTileEntity boardEntity = level.getBlockEntity(pos) instanceof CChessTileEntity be ? be : null;
        GameSession session = boardEntity != null
                ? boardEntity.ensureSession(getVariantId(state))
                : sm.get(key);
        if (session == null) {
            serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.board.invalid"));
            return InteractionResult.FAIL;
        }

        GameState gameState = session.getState();
        UUID me = serverPlayer.getUUID();

        // 终局后的棋盘曾永久停在 FINISHED:唯一的出路是 /qisheng reset。
        // 右键一块已经结束的棋盘 = "在这里开新局" —— 复位后走正常的加入流程。
        if (gameState == GameState.FINISHED) {
            resetFinishedBoard(sm, session, key);
            gameState = session.getState();
        }

        boolean alreadyIn = session.containsPlayer(me);
        boolean isSpectator = session.isSpectator(me);

        if (!alreadyIn && !isSpectator) {
            if (gameState == GameState.WAITING) {
                if (sm.joinGame(me, key)) {
                    if (session.getState() == GameState.PLAYING) {
                        boolean iAmRed = me.equals(session.getRedPlayer());
                        Component myRole = Component.translatable(iAmRed
                                ? "qisheng.chess.role.red"
                                : "qisheng.chess.role.black");
                        if (session.getMode() == BoardMode.PVC) {
                            serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.pvc.started"));
                        } else {
                            serverPlayer.sendSystemMessage(
                                    Component.translatable("qisheng.chess.join.started", myRole));
                        }
                        UUID otherId = iAmRed ? session.getBlackPlayer() : session.getRedPlayer();
                        ServerPlayer other = GameBroadcaster.findPlayer(serverLevel, otherId);
                        if (other != null) {
                            other.sendSystemMessage(
                                    Component.translatable("qisheng.chess.join.other_joined",
                                            serverPlayer.getName(), myRole));
                        }
                        GameBroadcaster.broadcastSync(serverLevel, session, pos);
                    } else {
                        serverPlayer.sendSystemMessage(GameMessages.joinedAsRed());
                    }
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                } else {
                    serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.join.failed"));
                }
            } else if (gameState == GameState.PLAYING) {
                // Empty slot? Prefer takeover so the game can keep going.
                boolean tookOver = false;
                if (session.getRedPlayer() == null && sm.takeOver(me, key, 0)) {
                    serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.takeover.took_red"));
                    tookOver = true;
                } else if (session.getBlackPlayer() == null && sm.takeOver(me, key, 1)) {
                    serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.takeover.took_black"));
                    tookOver = true;
                } else if (sm.joinAsSpectator(me, key)) {
                    serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.spectator.joined"));
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                } else {
                    serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.spectator.failed"));
                }
                if (tookOver) {
                    GameBroadcaster.broadcastSync(serverLevel, session, pos);
                    GameBroadcaster.broadcastRoster(serverLevel, session, pos);
                }
            }
        } else if (isSpectator) {
            // Already spectating — refresh the roster. If a slot has opened up,
            // prompt the player to consider takeover.
            GameBroadcaster.broadcastRoster(serverLevel, session, pos);
            if (session.getRedPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.takeover.slot_open_red"));
            } else if (session.getBlackPlayer() == null) {
                serverPlayer.sendSystemMessage(Component.translatable("qisheng.chess.takeover.slot_open_black"));
            }
        }
        // If alreadyIn, no chat update — the screen itself shows the latest state.

        // Always open the GUI on right-click so the player can interact.
        // Opening the board is also the one path that changes no state and
        // therefore broadcasts nothing — which is exactly what is needed after a
        // restart: it re-arms a PVC game whose turn had become the computer's.
        PvcController.maybeSchedule(serverLevel, pos, session);
        GameBroadcaster.sendOpenScreen(serverPlayer, session, pos);
        GameBroadcaster.broadcastRoster(serverLevel, session, pos);
        return InteractionResult.SUCCESS;
    }

    /** Common reset path used by every chess-family block. */
    public static void resetFinishedBoard(SessionManager sm, GameSession session, BoardKey key) {
        UUID red = session.getRedPlayer();
        UUID black = session.getBlackPlayer();
        session.setState(GameState.WAITING);
        session.setResult(GameResult.ONGOING);
        session.setSdPlayer(0);
        session.setSelectPoint(-1);
        session.setRedPlayer(null);
        session.setBlackPlayer(null);
        session.setLastMoveSource(-1);
        session.setLastMoveDest(-1);
        session.setPendingDrawFrom(null);
        session.setBoardState(session.getVariant().initialState());
        if (red != null) sm.evictPlayer(red);
        if (black != null) sm.evictPlayer(black);
    }

    // ---- Standard block properties used by every subclass ----

    /** Properties used by the xiangqi board (wood texture). */
    public static BlockBehaviour.Properties xiangqiProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f)
                .sound(SoundType.WOOD)
                .noOcclusion();
    }

    /** Properties used by the gomoku board (stone-tinted). */
    public static BlockBehaviour.Properties gomokuProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(2.5f)
                .sound(SoundType.STONE)
                .noOcclusion();
    }

    /** Properties used by the go board (dark oak feel). */
    public static BlockBehaviour.Properties goProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(2.5f)
                .sound(SoundType.WOOD)
                .noOcclusion();
    }
}
