package com.qisheng.chess.block;

/**
 * 五子棋棋盘方块(variant id = "gomoku")。15x15 全格,无功能差异。
 *
 * <p>视觉上用石头色 + 石头声,与中国象棋 / 围棋方块区分;行为完全复用
 * 父类 {@link AbstractChessBoardBlock} 的右键流程 —— 玩家一进入就是
 * WAITING,坐下即开始下棋,投子后自动判定胜负(父类不做渲染,
 * 棋盘渲染交给 {@link com.qisheng.chess.client.CChessBoardScreen} 的
 * variantId 分支)。
 *
 * <p>v0.4.21 起不再覆盖 {@code newBlockEntity} / {@code getVariantId(BlockState)}
 * —— 父类默认实现已正确返回 {@code new CChessTileEntity(pos, state)} 和
 * {@link #getVariantId()}。
 */
public class GomokuBoardBlock extends AbstractChessBoardBlock {

    public static final String VARIANT_ID = "gomoku";

    public GomokuBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public String getVariantId() {
        return VARIANT_ID;
    }
}