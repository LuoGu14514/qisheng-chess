package com.qisheng.chess.block;

import com.qisheng.chess.QishengChess;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * 方块注册中心(Architectury API)
 * Fabric + NeoForge 都通过同一套 API 注册
 *
 * MC 1.20.1 API 更新:
 *  - Registry.BLOCK_REGISTRY → Registries.BLOCK
 *  - Material + MaterialColor 删除 → BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
 */
public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<Block> CCHESS = BLOCKS.register("cchess", () ->
            new CChessBoardBlock(Block.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));
}