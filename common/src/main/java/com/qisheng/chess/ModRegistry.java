package com.qisheng.chess;

import com.qisheng.chess.block.ModBlocks;
import com.qisheng.chess.item.ModCreativeTabs;
import com.qisheng.chess.item.ModItems;
import com.qisheng.chess.tileentity.ModBlockEntities;

/**
 * Mod 总注册中心
 *
 * 在 common 初始化时(fabric + neoforge 都调)调用 {@link #init()}
 *
 * 注册顺序(必须):
 *  1. ModBlocks (DeferredRegister)
 *  2. ModItems (DeferredRegister)
 *  3. ModBlockEntities (DeferredRegister)
 *  4. ModCreativeTabs (DeferredRegister) + populateTabs()
 *  5. ModNetwork — 注册拆到 fabric / fabric.client entrypoint 调用 registerServer/registerClient
 *  (走 Fabric API,与 Architectury 类路径无关,避免 286 mods shade 旧版 ARK 时的 NoSuchMethodError)
 *
 * 简化 MVP:统一调每个类的 register()
 */
public final class ModRegistry {
    private ModRegistry() {}

    public static void init() {
        ModBlocks.BLOCKS.register();
        ModItems.ITEMS.register();
        ModBlockEntities.TILE_ENTITIES.register();
        ModCreativeTabs.TABS.register();
        ModCreativeTabs.populateTabs();
    }
}