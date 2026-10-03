package com.qisheng.chess.block;

import com.qisheng.chess.tileentity.CChessTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 围棋棋盘方块(variant id = "go9" 或 "go19",由 {@link #BOARD_SIZE} 决定)。
 *
 * <p>围棋有 9x9 和 19x19 两种常用尺寸,所以这里多带一个
 * {@link IntegerProperty} —— 默认 9,右键 / 命令都能改;每次玩家首次进入
 * 棋盘时,棋种 id 都会跟着尺寸在
 * {@link #getVariantId(BlockState)} 里同步算出。视觉用深色木纹,和五子棋 /
 * 象棋区分。
 */
public class GoBoardBlock extends AbstractChessBoardBlock {

    public static final String VARIANT_PREFIX = "go";

    /**
     * 棋盘路数(棋盘一边的交点数)。9 = 小棋盘 / 教学,19 = 标准。命令方块
     * / 玩家放置时都允许 9 或 19;其它数字会在 {@link #getVariantId(BlockState)}
     * 里被夹回 9 以避免越界。
     */
    public static final IntegerProperty BOARD_SIZE = IntegerProperty.create("size", 9, 19);

    public GoBoardBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(BOARD_SIZE, 9));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(BOARD_SIZE);
    }

    /**
     * Variant id 跟着 BlockState 走 —— {@code size=9} → {@code "go9"}、
     * {@code size=19} → {@code "go19"};其它(0..8 / 10..18)被夹回 9,
     * 避免围棋方块意外开一局象棋。
     */
    @Override
    public String getVariantId(BlockState state) {
        return variantIdForSize(state.getValue(BOARD_SIZE));
    }

    @Override
    public String getVariantId() {
        return getVariantId(this.defaultBlockState());
    }

    public static String variantIdForSize(int size) {
        if (size != 9 && size != 19) size = 9;
        return VARIANT_PREFIX + size;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CChessTileEntity(pos, state);
    }
}
