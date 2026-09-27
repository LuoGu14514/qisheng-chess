package com.qisheng.chess.neoforge;

import com.qisheng.chess.event.PlayerDisconnectHandler;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * NeoForge-side player event bridge to common {@link PlayerDisconnectHandler}.
 *
 * The corresponding Fabric-side mirror lives in {@code FabricEvents} and uses
 * {@code ServerPlayConnectionEvents.DISCONNECT}.
 */
public final class NeoForgeEvents {
    private NeoForgeEvents() {}

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            PlayerDisconnectHandler.onPlayerDisconnect(sp);
        }
    }
}