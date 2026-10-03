package com.qisheng.chess.tileentity;

import com.qisheng.chess.QishengChess;
import com.qisheng.chess.block.ModBlocks;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * TileEntity 注册中心(Architectury API)
 *
 * <p>v0.4 起三种棋盘方块共享同一个 {@link CChessTileEntity} 类型 —— 通过
 * {@link BlockEntityType.Builder#of(java.util.function.BiFunction, net.minecraft.world.level.block.Block...)}
 * 的可变参数列表把全部三种方块都绑到一个 BlockEntityType 上,这样:
 * <ul>
 *   <li>存档/区块数据不会因为多了一种棋盘方块就 fragment;</li>
 *   <li>{@code /give @s minecraft:knowledge_book} 之类的命令诊断时,只需检查
 *       一个 tile entity 类型;</li>
 *   <li>棋种信息来自 {@link CChessTileEntity#ensureSession(String)} 而不是
 *       BlockEntityType,所以同一个 tile entity 在 cchess / gomoku / go
 *       三种方块上都能正常工作。</li>
 * </ul>
 */
public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TILE_ENTITIES =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<CChessTileEntity>> CCHESS =
            TILE_ENTITIES.register("cchess", () ->
                    BlockEntityType.Builder.of(CChessTileEntity::new,
                            ModBlocks.CCHESS.get(),
                            ModBlocks.GOMOKU.get(),
                            ModBlocks.GO.get()).build(null));
}
