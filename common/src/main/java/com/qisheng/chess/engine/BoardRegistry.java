package com.qisheng.chess.engine;

import com.qisheng.chess.engine.go.GoVariant;
import com.qisheng.chess.engine.gomoku.GomokuVariant;
import com.qisheng.chess.engine.international.InternationalChessVariant;
import com.qisheng.chess.engine.xiangqi.XiangqiVariant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Static registry of every {@link BoardVariant} the mod ships.
 *
 * <p>Lookups by {@code id} power three things:
 * <ol>
 *   <li>NBT loading — a saved session that remembers its variant.</li>
 *   <li>Packet reading — the wire protocol carries the variant id alongside
 *   the FEN.</li>
 *   <li>Variant-aware game code (PVP / PVC) — looks up the variant from a
 *   session, then calls into it through {@link BoardVariant}.</li>
 * </ol>
 *
 * <p>The {@link #defaultVariant()} is what a freshly placed board uses, and
 * what every save from before this build falls back to (their NBT had no
 * variant tag).
 */
public final class BoardRegistry {

    /** Id used by every board predating v0.3.1 — they have no variant tag. */
    public static final String DEFAULT_ID = XiangqiVariant.ID;

    private static final Map<String, BoardVariant> VARIANTS;

    static {
        Map<String, BoardVariant> m = new LinkedHashMap<>();
        register(m, new XiangqiVariant());
        register(m, new InternationalChessVariant());
        register(m, new GomokuVariant());
        register(m, GoVariant.GO_9);
        register(m, GoVariant.GO_19);
        VARIANTS = Collections.unmodifiableMap(m);
    }

    private static void register(Map<String, BoardVariant> into, BoardVariant v) {
        BoardVariant prev = into.put(v.id(), v);
        if (prev != null) {
            throw new IllegalStateException("duplicate variant id: " + v.id());
        }
    }

    private BoardRegistry() {}

    /** Default variant — xiangqi, the original game this mod ships with. */
    public static BoardVariant defaultVariant() {
        return getById(DEFAULT_ID);
    }

    /**
     * Look up a variant by id.
     *
     * @throws IllegalArgumentException when {@code id} is null or unknown.
     *         Unknown ids point at a corrupted save or a packet from a newer
     *         client; neither is something to silently downplay.
     */
    public static BoardVariant getById(String id) {
        Objects.requireNonNull(id, "variant id must not be null");
        BoardVariant v = VARIANTS.get(id);
        if (v == null) {
            throw new IllegalArgumentException("unknown variant: " + id);
        }
        return v;
    }

    /** Defensive variant lookup that falls back to the default on miss. */
    public static BoardVariant getByIdOrDefault(String id) {
        BoardVariant v = VARIANTS.get(id);
        return v != null ? v : defaultVariant();
    }

    /** Read-only view of every registered variant, in insertion order. */
    public static Map<String, BoardVariant> all() {
        return VARIANTS;
    }
}