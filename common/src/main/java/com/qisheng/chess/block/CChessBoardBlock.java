package com.qisheng.chess.block;

/**
 * 中国象棋棋盘方块(variant id = "xiangqi")。
 *
 * <p>v0.4 起所有共享逻辑都在 {@link AbstractChessBoardBlock} 父类里,本类
 * 只剩"我是哪一种棋盘"这一个声明 + 一个静态便捷引用,方便旧调用点
 * (ModBlocks / 资源包 ID 等)继续按原名找到我。
 */
public class CChessBoardBlock extends AbstractChessBoardBlock {

    public static final String VARIANT_ID = "xiangqi";

    public CChessBoardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public String getVariantId() {
        return VARIANT_ID;
    }
}
