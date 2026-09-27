package com.qisheng.chess.neoforge;

import com.qisheng.chess.ModRegistry;
import com.qisheng.chess.QishengChess;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
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


