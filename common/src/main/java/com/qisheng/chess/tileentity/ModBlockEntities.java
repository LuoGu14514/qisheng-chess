package com.qisheng.chess.tileentity;

import com.qisheng.chess.QishengChess;
import com.qisheng.chess.block.ModBlocks;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * TileEntity 注册中心(Architectury API)
 *
 * <p>v0.4 起所有棋盘方块共享同一个 {@link CChessTileEntity} 类型 —— 通过
 * {@link BlockEntityType.Builder#of(java.util.function.BiFunction, net.minecraft.world.level.block.Block...)}
 * 的可变参数列表把全部方块都绑到一个 BlockEntityType 上,这样:
 * <ul>
 *   <li>存档/区块数据不会因为多了一种棋盘方块就 fragment;</li>
 *   <li>诊断命令只需检查一个 tile entity 类型;</li>
 *   <li>棋种信息来自 {@link CChessTileEntity#ensureSession(String)} 而不是
 *       BlockEntityType,所以同一个 tile entity 在 cchess / gomoku / go9 / go19
 *       四种方块上都能正常工作。</li>
 * </ul>
 */
public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> TILE_ENTITIES =
            DeferredRegister.create(QishengChess.MOD_ID, net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE);

    public static final RegistrySupplier<BlockEntityType<CChessTileEntity>> CCHESS =
            TILE_ENTITIES.register("cchess", () ->
                    BlockEntityType.Builder.of(CChessTileEntity::new,
                            ModBlocks.CCHESS.get(),
                            ModBlocks.GOMOKU.get(),
                            ModBlocks.GO9.get(),
                            ModBlocks.GO19.get()).build(null));
}
