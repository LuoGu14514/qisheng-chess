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
 * MC 1.20.1 API 更新:
 *  - Registry.BLOCK_ENTITY_TYPE_REGISTRY → Registries.BLOCK_ENTITY_TYPE
 */
public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TILE_ENTITIES =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<CChessTileEntity>> CCHESS =
            TILE_ENTITIES.register("cchess", () ->
                    BlockEntityType.Builder.of(CChessTileEntity::new, ModBlocks.CCHESS.get()).build(null));
}