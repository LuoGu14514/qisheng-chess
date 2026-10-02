package com.qisheng.chess.engine;

/**
 * Marker interface for a chess-variant's board state object.
 *
 * <p>A {@code BoardState} is whatever concrete structure a
 * {@link BoardVariant} uses to keep its squares — a {@code Position}
 * (xiangqi) for one variant, a {@code IntChessBoard} (international chess)
 * for another. Variants consume it through their own methods, and code
 * outside a variant should pass it around as this marker so a single
 * {@code GameSession} can hold a board for any variant without committing
 * to the variant at compile time.
 *
 * <p>Implementation contract:
 * <ul>
 *   <li>Mutable, owned by exactly one {@code GameSession}.</li>
 *   <li>Equality / toString are not part of the contract — never use
 *   board objects as map keys.</li>
 * </ul>
 */
public interface BoardState {
}