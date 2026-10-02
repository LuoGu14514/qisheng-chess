package com.qisheng.chess.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
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
 *
 * <h2>Bounds</h2>
 * The cache used to grow forever and never expired, so a long session with
 * many visitors leaked entries and a skin that failed to resolve was cached
 * permanently. It is now an access-ordered LRU with
 * {@link #MAX_ENTRIES} entries and a {@link #TTL_MS} time-to-live; the
 * fallback path after a failed lookup gets the shorter
 * {@link #FALLBACK_TTL_MS} so a transient failure is retried soon.
 *
 * <p>All callers run on the render thread (widgets only), so the map needs no
 * synchronisation.
 */
public final class PlayerAvatarCache {

    /** Hard cap on cached entries; the least recently used one is evicted. */
    private static final int MAX_ENTRIES = 128;
    /** How long a successfully resolved skin is trusted. */
    private static final long TTL_MS = 5 * 60 * 1000L;
    /** Much shorter TTL for a fallback skin cached after a failed lookup. */
    private static final long FALLBACK_TTL_MS = 30 * 1000L;

    /** Access-ordered LRU: {@code get} moves the entry to the back. */
    private static final Map<UUID, Entry> CACHE =
            new LinkedHashMap<>(16, 0.75F, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private PlayerAvatarCache() {}

    public static ResourceLocation get(UUID id) {
        if (id == null) return DefaultPlayerSkin.getDefaultSkin();

        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(id);
        if (cached != null && now < cached.expiresAt()) return cached.skin();

        ResourceLocation resolved;
        long ttl;
        try {
            GameProfile profile = new GameProfile(id, null);
            resolved = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
            ttl = TTL_MS;
        } catch (Throwable t) {
            // Never cache a broken fallback forever.
            resolved = DefaultPlayerSkin.getDefaultSkin(id);
            ttl = FALLBACK_TTL_MS;
        }
        CACHE.put(id, new Entry(resolved, now + ttl));
        return resolved;
    }

    public static void invalidate(UUID id) { CACHE.remove(id); }
    public static void clear() { CACHE.clear(); }

    /** One cached lookup with its expiry stamp. */
    private record Entry(ResourceLocation skin, long expiresAt) {}
}
