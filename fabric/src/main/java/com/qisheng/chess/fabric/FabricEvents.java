package com.qisheng.chess.fabric;

import com.qisheng.chess.event.PlayerDisconnectHandler;
import com.qisheng.chess.command.ModCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Fabric-side event registrations that cannot live in common code:
 *   - /qisheng command via Fabric's CommandRegistrationCallback
 *   - Player disconnect via ServerPlayConnectionEvents.DISCONNECT, which
 *     delegates to {@link PlayerDisconnectHandler} on the server side.
 *
 * NeoForge has its own mirror in {@code NeoForgeEvents}.
 */
public final class FabricEvents {
    private FabricEvents() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ModCommands.register(dispatcher);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlayerDisconnectHandler.onPlayerDisconnect(handler.getPlayer());
        });
    }
}