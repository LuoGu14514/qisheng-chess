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
 * <p>v0.4 新增三个棋盘方块:
 * <ul>
 *   <li>{@code gomoku} — 五子棋(15x15),Stone 色调</li>
 *   <li>{@code go9}    — 围棋 9 路(教学 / 快速对局)</li>
 *   <li>{@code go19}   — 围棋 19 路(标准)</li>
 * </ul>
 *
 * <p>四种棋盘共用同一个 {@code BlockEntityType}(见
 * {@link com.qisheng.chess.tileentity.ModBlockEntities}),棋种在
 * 各方块自己的 {@code getVariantId()} 处决定。
 */
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> CCHESS = BLOCKS.register("cchess", () ->
            new CChessBoardBlock(AbstractChessBoardBlock.xiangqiProperties()));

    public static final RegistrySupplier<Block> GOMOKU = BLOCKS.register("gomoku", () ->
            new GomokuBoardBlock(AbstractChessBoardBlock.gomokuProperties()));

    public static final RegistrySupplier<Block> GO9 = BLOCKS.register("go9", () ->
            new GoBoard9Block(AbstractChessBoardBlock.goProperties()));

    public static final RegistrySupplier<Block> GO19 = BLOCKS.register("go19", () ->
            new GoBoard19Block(AbstractChessBoardBlock.goProperties()));
}
