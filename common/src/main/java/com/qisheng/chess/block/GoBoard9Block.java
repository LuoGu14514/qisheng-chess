package com.qisheng.chess.block;

import com.qisheng.chess.tileentity.CChessTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 9 路围棋棋盘方块(variant id 固定为 {@code "go9"})。
 *
 * <p>v0.4.2 起,围棋不再用单个方块 + {@code size} BlockState 切换,而是
 * 拆成两个独立方块 (本类 + {@link GoBoard19Block}),各自在创造模式物品栏
 * 独立显示,玩家拿到的物品直接对应一个尺寸,不需要右键后改尺寸。
 */
public class GoBoard9Block extends AbstractChessBoardBlock {

    public GoBoard9Block(Properties properties) {
        super(properties);
    }

    @Override
    public String getVariantId() {
        return "go9";
    }

    @Override
    public String getVariantId(BlockState state) {
        return "go9";
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CChessTileEntity(pos, state);
    }
}
