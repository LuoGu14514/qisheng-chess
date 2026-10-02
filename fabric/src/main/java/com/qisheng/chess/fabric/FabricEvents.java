package com.qisheng.chess.fabric;

import com.qisheng.chess.QishengChess;
import com.qisheng.chess.command.ModCommands;
import com.qisheng.chess.event.PlayerDisconnectHandler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Fabric-side event registrations that cannot live in common code:
 *   - /qisheng command via Fabric's CommandRegistrationCallback
 *   - Player disconnect via ServerPlayConnectionEvents.DISCONNECT, which
 *     delegates to {@link PlayerDisconnectHandler} on the server side.
 *   - Server start via ServerLifecycleEvents.SERVER_STARTED, which clears the
 *     process-wide {@code SessionManager} singleton. Without it a single JVM
 *     that hosts several worlds in a row (leaving one single-player world and
 *     loading another) would inherit the previous world's boards. This is
 *     deliberately on STARTED and not on STOPPING: sessions are what gets
 *     written into the boards' tile-entity NBT during the final chunk save,
 *     and SERVER_STOPPING fires before that save.
 *
 * There is no NeoForge mirror any more — the neoforge module was removed.
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

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            QishengChess.onServerStarted();
        });
    }
}