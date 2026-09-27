package com.qisheng.chess.neoforge;

import com.qisheng.chess.ModRegistry;
import com.qisheng.chess.QishengChess;
import com.qisheng.chess.tileentity.ModBlockEntities;
import com.qisheng.chess.client.CChessBoardBER;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(QishengChess.MOD_ID)
public class QishengChessNeoForge {
    public static final Logger LOGGER = LoggerFactory.getLogger(QishengChess.MOD_ID);

    public QishengChessNeoForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.init();
        // NeoForgeEvents must be registered on the GAME bus so
        // PlayerLoggedOutEvent fires correctly.
        NeoForge.EVENT_BUS.register(NeoForgeEvents.class);
        LOGGER.info("[" + QishengChess.MOD_ID + "] NeoForge mod initialized");

        // Client-only: register the board BER. Calling
        // com.qisheng.chess.client.ModClient.registerBlockEntityRenderers()
        // here is a no-op on NeoForge (the @ExpectPlatformImpl body just
        // returns), but we use the same cross-platform hook so Fabric /
        // NeoForge stay in lock-step. The actual registration happens via
        // onRegisterRenderers() below on the client side.
        com.qisheng.chess.client.ModClient.registerBlockEntityRenderers();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(QishengChessNeoForge::onRegisterRenderers);
        }
    }

    /** Client-only mod-bus listener for {@link RegisterRenderersEvent}. */
    private static void onRegisterRenderers(RegisterRenderersEvent event) {
        event.registerBlockEntityRenderer(ModBlockEntities.CCHESS.get(), CChessBoardBER::new);
        LOGGER.info("[" + QishengChess.MOD_ID + "] NeoForge client: registered CChessBoardBER");
    }
}