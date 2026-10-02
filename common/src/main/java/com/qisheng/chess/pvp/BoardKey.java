package com.qisheng.chess.pvp;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Identity of a single chess board: a block position <em>inside one dimension</em>.
 *
 * <p>Keying sessions by {@link BlockPos} alone made a board at {@code (10,64,10)}
 * in the Overworld and a board at the same coordinates in the Nether share one
 * {@link GameSession} — two unrelated games collapsed into one, with the players
 * of one board seeing (and fighting over) the pieces of the other.
 *
 * <p>{@link BlockPos} is mutable and compares by value, so the record stores an
 * {@link BlockPos#immutable() immutable} copy and never hands out the original.
 */
public record BoardKey(ResourceKey<Level> dimension, BlockPos pos) {

    public BoardKey {
        // Defensive copy: BlockPos is a mutable Vec3i subclass.
        pos = pos.immutable();
    }

    public static BoardKey of(ResourceKey<Level> dimension, BlockPos pos) {
        return new BoardKey(dimension, pos);
    }

    /** Prefer this overload when a {@link Level} is at hand. */
    public static BoardKey of(Level level, BlockPos pos) {
        return new BoardKey(level.dimension(), pos);
    }

    /** Short human-readable form for command output / log lines. */
    public String describe() {
        return dimension.location() + " " + pos.toShortString();
    }
}
