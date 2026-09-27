package com.qisheng.chess.tileentity;

import com.qisheng.chess.pvp.GameSession;
import com.qisheng.chess.pvp.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 中国象棋棋盘方块的 TileEntity
 * - 不存数据(所有数据在 SessionManager 的内存里)
 * - 只用来标记"这个方块是棋盘",以及持有对局引用
 *
 * Phase 2 才把数据保存到 NBT(目前重启后对局清空)
 */
public class CChessTileEntity extends BlockEntity {

    public CChessTileEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CCHESS.get(), pos, state);
    }

    /** 获取本棋盘的对局(如果有) */
    public GameSession getSession() {
        if (level instanceof ServerLevel serverLevel) {
            return SessionManager.get().get(worldPosition);
        }
        return null;
    }
}