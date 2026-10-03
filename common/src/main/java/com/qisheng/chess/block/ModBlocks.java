package com.qisheng.chess.block;

import com.qisheng.chess.QishengChess;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;

/**
 * 方块注册中心(Architectury API)
 * Fabric + NeoForge 都通过同一套 API 注册
 *
 * <p>v0.4 新增两个棋盘方块:
 * <ul>
 *   <li>{@code gomoku} — 五子棋(15x15),Stone 色调</li>
 *   <li>{@code go}     — 围棋(默认 9x9,通过 {@code size} BlockStateProperty
 *       可在 9/19 之间切换)</li>
 * </ul>
 *
 * <p>三种棋盘共用同一个 {@code BlockEntityType}(见
 * {@link com.qisheng.chess.tileentity.ModBlockEntities}),棋种在
 * {@link AbstractChessBoardBlock#getVariantId(BlockState)} 处按方块
 * 类型决定。
 */
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> CCHESS = BLOCKS.register("cchess", () ->
            new CChessBoardBlock(AbstractChessBoardBlock.xiangqiProperties()));

    public static final RegistrySupplier<Block> GOMOKU = BLOCKS.register("gomoku", () ->
            new GomokuBoardBlock(AbstractChessBoardBlock.gomokuProperties()));

    public static final RegistrySupplier<Block> GO = BLOCKS.register("go", () ->
            new GoBoardBlock(AbstractChessBoardBlock.goProperties()));
}
