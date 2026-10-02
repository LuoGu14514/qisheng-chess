package com.qisheng.chess.engine.xiangqi;

import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.Move;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.util.CChessUtil;

/**
 * {@link BoardVariant} for xiangqi.
 *
 * <p>The state object <em>is</em> the bundled xqwlight
 * {@link com.qisheng.chess.engine.xqwlight.Position} — no wrapping, no copy.
 * Every method on this class is a thin delegation so that callers can stop
 * caring about xiangqi-specific byte codes and {@code Position.MOVE(src,dst)}
 * encoding at the same time.
 *
 * <p>The {@link ChineseChessEngine} static facade stays as-is for the rest of
 * the mod (it has its own well-tested gate logic). v0.3.1 only adds the
 * variant shim alongside it; later milestones can replace callers with the
 * shim, but the static facade will keep working for anything that still
 * touches {@code Position} directly.
 */
public final class XiangqiVariant implements BoardVariant {

    /** Stable id, persisted in NBT and shipped on the wire. */
    public static final String ID = "xiangqi";

    public XiangqiVariant() {}

    @Override public String id() { return ID; }
    @Override public String displayNameKey() { return "qisheng.chess.variant.xiangqi"; }

    @Override public int boardFiles() { return 9; }
    @Override public int boardRanks() { return 10; }
    @Override public int totalSquares() { return ChineseChessEngine.SQUARE_ARRAY_SIZE; }

    @Override public String initialFen() {
        return CChessUtil.INIT;
    }

    @Override public BoardState initialState() {
        Position p = new Position();
        p.fromFen(initialFen());
        return p;
    }

    @Override public BoardState parseState(String fen) {
        Position p = ChineseChessEngine.parseFen(fen);
        return p;
    }

    @Override public boolean isValidSquare(int sq) {
        return ChineseChessEngine.isSquare(sq);
    }

    @Override public byte pieceAt(BoardState state, int sq) {
        if (!(state instanceof Position pos)) return 0;
        return ChineseChessEngine.pieceAt(pos, sq);
    }

    @Override public int sideOfPiece(BoardState state, int sq) {
        if (!(state instanceof Position pos)) return -1;
        byte pc = ChineseChessEngine.pieceAt(pos, sq);
        if (pc == 0) return -1;
        return CChessUtil.isRed(pc) ? 0 : 1;
    }

    @Override public boolean canMove(BoardState state, int src, int dst) {
        if (!(state instanceof Position pos)) return false;
        return ChineseChessEngine.canMove(pos, src, dst);
    }

    @Override public boolean applyMove(BoardState state, int src, int dst) {
        if (!(state instanceof Position pos)) return false;
        return ChineseChessEngine.applyMove(pos, src, dst);
    }

    @Override public String toFen(BoardState state) {
        if (!(state instanceof Position pos)) return initialFen();
        return pos.toFen();
    }

    @Override public int sideToMove(BoardState state) {
        if (state instanceof Position pos) return pos.sdPlayer;
        return 0;
    }

    @Override public void setSideToMove(BoardState state, int sd) {
        if (state instanceof Position pos) pos.sdPlayer = sd;
    }

    @Override public Move searchBestMove(String fen, int depth, int millis) {
        int mv = ChineseChessEngine.searchBestMove(fen, depth, millis);
        return mv > 0 ? new Move(Position.SRC(mv), Position.DST(mv)) : Move.NONE;
    }

    @Override public Move firstLegalMove(String fen) {
        int mv = ChineseChessEngine.firstLegalMove(fen);
        return mv > 0 ? new Move(Position.SRC(mv), Position.DST(mv)) : Move.NONE;
    }

    @Override public boolean isInCheck(BoardState state, int side) {
        if (!(state instanceof Position pos)) return false;
        // xqwlight only exposes isChecked() for the side to move. Save / flip
        // sdPlayer, ask, flip back — never mutate the caller's state.
        int saved = pos.sdPlayer;
        try {
            pos.sdPlayer = side;
            return pos.checked();
        } finally {
            pos.sdPlayer = saved;
        }
    }

    @Override public boolean isCheckmate(BoardState state) {
        if (!(state instanceof Position pos)) return false;
        return pos.isMate();
    }

    @Override public boolean isStalemate(BoardState state) {
        // xiangqi has no stalemate under the official rules — bare stalemate is
        // a perpetual-check loss, which surfaces through isMate() / repStatus()
        // upstream. Keep the predicate honest for downstream users.
        return false;
    }

    @Override public char pieceFenChar(BoardState state, int sq) {
        if (!(state instanceof Position pos)) return '.';
        byte pc = ChineseChessEngine.pieceAt(pos, sq);
        if (pc == 0) return '.';
        // base type letters: lowercase = black, uppercase = red.
        // xqwlight piece code = base in low 3 bits, side in bits 3-4. We map
        // each base to its display letter directly without going through
        // ChineseChessEngine.PIECE_LETTERS (which is private).
        char base = switch (pc & 7) {
            case 1 -> 'k'; // 将/帅
            case 2 -> 'a'; // 士/仕
            case 3 -> 'b'; // 象/相
            case 4 -> 'n'; // 马
            case 5 -> 'r'; // 车
            case 6 -> 'c'; // 炮
            default -> '.';
        };
        return CChessUtil.isRed(pc) ? Character.toUpperCase(base) : base;
    }

    @Override public int legalDestsBitmapSize() {
        return 256 / 8;
    }
}