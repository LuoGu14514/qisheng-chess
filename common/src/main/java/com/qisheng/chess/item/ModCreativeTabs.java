package com.qisheng.chess.item;

import com.qisheng.chess.QishengChess;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Creative tab registration.
 *
 * Architectury's {@link CreativeTabRegistry} is platform-agnostic — same call
 * works for Fabric and NeoForge. The tab icon uses the chess board block item
 * itself so the tab name and the only item are visually consistent.
 *
 * Note: {@code CreativeModeTab.Builder} requires a {@link net.minecraft.resources.ResourceKey}
 * on 1.20.1; we let Architectury generate one from the supplied name.
 */
public final class ModCreativeTabs {
    private ModCreativeTabs() {}

    /** DeferredRegister is required by Architectury even for tabs. */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(QishengChess.MOD_ID, Registries.CREATIVE_MODE_TAB);

    /** Single tab: contains only the chess board item (MVP). */
    public static final RegistrySupplier<CreativeModeTab> CCHESS_TAB = TABS.register(
            "qisheng_chess",
            () -> CreativeTabRegistry.create(
                    Component.translatable("itemGroup." + QishengChess.MOD_ID),
                    () -> new ItemStack(ModItems.CCHESS.get())
            )
    );

    /**
     * Append additional items to the tab. Called from {@code ModRegistry.init()}
     * after both ITEMS and TABS have been registered.
     *
     * {@code appendStack} takes a {@code Supplier<ItemStack>}; we wrap the
     * lazy {@code RegistrySupplier<Item>} so the item is resolved at the
     * point of tab population, not at class-load time.
     */
    public static void populateTabs() {
        CreativeTabRegistry.appendStack(CCHESS_TAB, () -> new ItemStack(ModItems.CCHESS.get()));
        CreativeTabRegistry.appendStack(CCHESS_TAB, () -> new ItemStack(ModItems.GOMOKU.get()));
        CreativeTabRegistry.appendStack(CCHESS_TAB, () -> new ItemStack(ModItems.GO9.get()));
        CreativeTabRegistry.appendStack(CCHESS_TAB, () -> new ItemStack(ModItems.GO19.get()));
    }
}