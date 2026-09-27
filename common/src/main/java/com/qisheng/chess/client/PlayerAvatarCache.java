package com.qisheng.chess.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin cache mapping {@code UUID → head-skin ResourceLocation}.
 *
 * <p>Resolution order on a miss:
 * <ol>
 *   <li>{@code Minecraft.getSkinManager().getInsecureSkinLocation(profile)}
 *       — returns a {@link ResourceLocation} pointing at the 8×8 skin atlas.
 *       If the player has no Mojang skin this falls back to Steve / Alex.</li>
 *   <li>{@code DefaultPlayerSkin.getDefaultSkin(id)} — final fallback
 *       used while the SkinManager hasn't resolved the skin yet (e.g.
 *       offline server, no Mojang auth).</li>
 * </ol>
 *
 * <p>Both layers return quickly (no network). Real custom skins uploaded to
 * a server-side {@code Skins} mod would need a separate async fetch — not
 * in scope here.
 */
public final class PlayerAvatarCache {

    private static final Map<UUID, ResourceLocation> CACHE = new HashMap<>();

    private PlayerAvatarCache() {}

    public static ResourceLocation get(UUID id) {
        if (id == null) return DefaultPlayerSkin.getDefaultSkin();
        ResourceLocation cached = CACHE.get(id);
        if (cached != null) return cached;

        ResourceLocation resolved;
        try {
            GameProfile profile = new GameProfile(id, null);
            resolved = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        } catch (Throwable t) {
            resolved = DefaultPlayerSkin.getDefaultSkin(id);
        }
        CACHE.put(id, resolved);
        return resolved;
    }

    public static void invalidate(UUID id) { CACHE.remove(id); }
    public static void clear() { CACHE.clear(); }
}