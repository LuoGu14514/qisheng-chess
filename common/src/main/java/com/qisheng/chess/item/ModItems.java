package com.qisheng.chess.item;

import com.qisheng.chess.QishengChess;
import com.qisheng.chess.block.ModBlocks;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * Item registration (Architectury API)
 *
 * <p>v0.4 之前只注册了 CCHESS 一个 BlockItem,导致五子棋 / 围棋方块在
 * 创造模式物品栏里看不到,只能 /setblock。v0.4.1 起四个棋盘方块都
 * 注册对应的 BlockItem,玩家拿到的物品直接对应一个棋类。
 *
 * <p>围棋 v0.4.2 起拆成 GO9 + GO19 两个独立 BlockItem (不再用
 * 单个方块 + {@code size} BlockState 切换尺寸),方便玩家直接选尺寸。
 */
public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<Item> CCHESS = ITEMS.register("cchess", () ->
            new BlockItem(ModBlocks.CCHESS.get(), new Item.Properties()));

    public static final RegistrySupplier<Item> GOMOKU = ITEMS.register("gomoku", () ->
            new BlockItem(ModBlocks.GOMOKU.get(), new Item.Properties()));

    public static final RegistrySupplier<Item> GO9 = ITEMS.register("go9", () ->
            new BlockItem(ModBlocks.GO9.get(), new Item.Properties()));

    public static final RegistrySupplier<Item> GO19 = ITEMS.register("go19", () ->
            new BlockItem(ModBlocks.GO19.get(), new Item.Properties()));
}
