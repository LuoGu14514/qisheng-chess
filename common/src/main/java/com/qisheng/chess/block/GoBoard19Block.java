package com.qisheng.chess.block;

/**
 * 19 路围棋棋盘方块(variant id = "go19")。
 *
 * <p>v0.4.2 起,围棋不再用单个方块 + {@code size} BlockState 切换,而是
 * 拆成两个独立方块 (本类 + {@link GoBoard9Block}),各自在创造模式物品栏
 * 独立显示,玩家拿到的物品直接对应一个尺寸。
 *
 * <p>v0.4.21 起不再覆盖 {@code newBlockEntity} / {@code getVariantId(BlockState)}
 * —— 父类默认实现已正确返回 {@code new CChessTileEntity(pos, state)} 和
 * {@link #getVariantId()}。
 */
public class GoBoard19Block extends AbstractChessBoardBlock {

    public static final String VARIANT_ID = "go19";

    public GoBoard19Block(Properties properties) {
        super(properties);
    }

    @Override
    public String getVariantId() {
        return VARIANT_ID;
    }
}