package com.qisheng.chess.engine.xqwlight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.qisheng.chess.util.CChessUtil.INIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization + regression tests for the bundled xqwlight engine.
 *
 * <p>These pin down the behaviour the rest of the mod leans on: FEN parsing
 * (which returns {@code null} rather than throwing), make/undo symmetry, and —
 * most importantly — the fact that {@code Position}'s mutators are
 * <em>not</em> defensive. {@link ChineseChessEngineTest} covers the guarded
 * facade that the game code actually calls.
 *
 * <p>The engine is plain Java (no Minecraft types), so this runs as an ordinary
 * JVM unit test.
 */
class PositionTest {

    private static final int MAX_MOVES = 256;

    /** {@code file} 0..8 (a..i from red's left), {@code rank} 0..9 (0 = red's back rank). */
    private static int sq(int file, int rank) {
        return Position.COORD_XY(file + Position.FILE_LEFT, rank + Position.RANK_TOP);
    }

    private static Position initial() {
        Position p = Position.fromFenString(INIT);
        assertNotNull(p, "the standard opening FEN must parse");
        return p;
    }

    @Test
    @DisplayName("initial position parses and round-trips through FEN")
    void initialPositionRoundTrips() {
        Position p = initial();
        String fen = p.toFen();
        Position again = Position.fromFenString(fen);
        assertNotNull(again, "our own toFen() output must be re-parseable");
        assertEquals(fen, again.toFen(), "FEN round-trip must be stable");
    }

    @Test
    @DisplayName("red has the standard 44 opening moves")
    void openingMoveCount() {
        Position p = initial();
        int[] mvs = new int[MAX_MOVES];
        int n = p.generateAllMoves(mvs);
        assertEquals(44, n, "the xiangqi opening position has 44 legal moves");
        assertEquals(0, p.sdPlayer, "red moves first");
    }

    @Test
    @DisplayName("legalMove rejects a move onto a friendly piece")
    void friendlyFireRejected() {
        Position p = initial();
        int rook = sq(0, 0);        // red's left rook
        int ownKnight = sq(1, 0);   // red's left knight
        assertFalse(p.legalMove(Position.MOVE(rook, ownKnight)),
                "a move onto one's own piece must be refused");
        assertFalse(p.legalMove(Position.MOVE(rook, rook)),
                "a null move must be refused");
        assertFalse(p.legalMove(Position.MOVE(sq(4, 9), sq(0, 0))),
                "black may not move while red is to move");
    }

    /**
     * Executable documentation for why {@link ChineseChessEngine} exists.
     *
     * <p>{@code Position.makeMove} performs no validation at all: on an empty
     * source square it runs {@code movePiece() → delPiece(sq, 0)}, which indexes
     * {@code PIECE_VALUE[0 - 8]}. The guarded facade turns this into a clean
     * {@code false}.
     */
    @Test
    @DisplayName("raw makeMove() on an empty source square throws (why the facade exists)")
    void rawMakeMoveIsNotDefensive() {
        Position p = initial();
        int empty = sq(4, 4);       // centre of the board, empty at the start
        int somewhere = sq(4, 5);
        assertEquals(0, p.squares[empty], "precondition: the square must be empty");
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> p.makeMove(Position.MOVE(empty, somewhere)),
                "the raw engine is documented to blow up here");
    }

    @Test
    @DisplayName("a refused legalMove leaves the board completely untouched")
    void rejectedMoveIsSideEffectFree() {
        Position p = initial();
        String before = p.toFen();
        int baseMoveNum = p.moveNum;
        int rook = sq(0, 0);
        int ownKnight = sq(1, 0);
        assertFalse(p.legalMove(Position.MOVE(rook, ownKnight)));
        assertEquals(before, p.toFen(), "board, side to move and counters must be unchanged");
        assertEquals(baseMoveNum, p.moveNum, "moveNum must not advance on a refused move");
        assertFalse(p.inCheck(), "a refused move must not report a check");
    }

    @Test
    @DisplayName("makeMove / undoMakeMove restores the exact position")
    void makeUndoIsSymmetric() {
        Position p = initial();
        String before = p.toFen();
        int baseMoveNum = p.moveNum;
        int baseDistance = p.distance;
        int[] mvs = new int[MAX_MOVES];
        int n = p.generateAllMoves(mvs);
        int exercised = 0;
        for (int i = 0; i < n; i++) {
            int mv = mvs[i];
            if (!p.legalMove(mv)) continue;
            assertTrue(p.makeMove(mv), "legalMove() must agree with makeMove()");
            assertEquals(baseMoveNum + 1, p.moveNum);
            assertEquals(baseDistance + 1, p.distance);
            assertEquals(1, p.sdPlayer, "side to move flips after a move");

            p.undoMakeMove();
            assertEquals(before, p.toFen(), "undo must restore the position exactly");
            assertEquals(baseMoveNum, p.moveNum);
            assertEquals(baseDistance, p.distance);
            assertEquals(0, p.sdPlayer);
            exercised++;
        }
        assertEquals(n, exercised, "every generated opening move must be legal");
    }

    @Test
    @DisplayName("null move is a no-op once undone")
    void nullMoveRoundTrips() {
        Position p = initial();
        String before = p.toFen();
        p.nullMove();
        p.undoNullMove();
        assertEquals(before, p.toFen());
    }

    @Test
    @DisplayName("fromFenString never returns null and never rejects input")
    void fromFenStringDoesNotValidate() {
        // Documented surprise (this is why ChineseChessEngine.isWellFormedFen
        // exists): there is no failure path in the engine at all. Blank input
        // yields an empty board, junk yields a populated nonsense board, and
        // null throws.
        Position blank = Position.fromFenString("");
        assertNotNull(blank);
        int pieces = 0;
        for (int i = 0; i < blank.squares.length; i++) {
            if (blank.squares[i] != 0) pieces++;
        }
        assertEquals(0, pieces, "a blank FEN yields an empty board, not null");

        assertNotNull(Position.fromFenString("q/9/9/9/9/9/9/9/9/9"));
        assertThrows(NullPointerException.class, () -> Position.fromFenString(null));
    }

    @Test
    @DisplayName("COORD_XY / FILE_X / RANK_Y round-trip and 90 points are on the board")
    void coordinateHelpers() {
        int onBoard = 0;
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                int square = Position.COORD_XY(x + Position.FILE_LEFT, y + Position.RANK_TOP);
                assertEquals(x + Position.FILE_LEFT, Position.FILE_X(square));
                assertEquals(y + Position.RANK_TOP, Position.RANK_Y(square));
                if (Position.IN_BOARD(square)) onBoard++;
            }
        }
        assertEquals(90, onBoard, "the board has exactly 90 playable points");
    }

    @Test
    @DisplayName("mirror mirrors the board and is its own inverse")
    void mirrorIsIndependent() {
        Position p = initial();
        Position m = p.mirror();
        assertNotNull(m);
        assertNotSame(p, m);
        // mirror() only flips the side when it was black's turn, so from the
        // opening position both stay on red.
        assertEquals(p.sdPlayer, m.sdPlayer);
        assertEquals(p.toFen(), m.mirror().toFen(), "mirroring twice is the identity");
    }

    @Test
    @DisplayName("Move encoding helpers agree with MOVE/SRC/DST")
    void moveEncoding() {
        int src = sq(0, 0);
        int dst = sq(0, 1);
        int mv = Position.MOVE(src, dst);
        assertEquals(src, Position.SRC(mv));
        assertEquals(dst, Position.DST(mv));
    }

    private static void assertNotSame(Object a, Object b) {
        assertFalse(a == b, "expected two distinct instances");
    }
}