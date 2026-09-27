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
 * MC 1.20.1 note: TAB_MISC was removed from CreativeModeTabs (no public
 * Misc tab anymore). Items without a tab are still functional; they can
 * still be spawned via /give or picked up in survival. Add to a tab later
 * via FabricItemGroup.builder() or ItemGroupEvents if needed.
 */
public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<Item> CCHESS = ITEMS.register("cchess", () ->
            new BlockItem(ModBlocks.CCHESS.get(), new Item.Properties()));
}