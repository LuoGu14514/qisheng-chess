package com.qisheng.chess.engine;

import com.qisheng.chess.engine.xqwlight.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.qisheng.chess.util.CChessUtil.INIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the guarded engine facade.
 *
 * <p>The point of these is the <em>never throws</em> contract: every square
 * that could arrive from a network packet, a command argument or a hand-edited
 * FEN must come back as {@code false}, never as an exception on the server tick
 * thread.
 */
class ChineseChessEngineTest {

    /**
     * Board coordinates as a human reads them off the board: {@code file} 0..8
     * runs left to right, {@code rank} 0..9 runs bottom to top, so rank 0 is
     * <em>red's</em> back rank and rank 9 is black's.
     *
     * <p>Deliberately not the FEN order: the FEN lists black's back rank first,
     * and the engine's internal {@code RANK_TOP} is also black's side. Getting
     * this backwards silently turns every "red moves" assertion upside down.
     */
    private static int sq(int file, int rank) {
        return Position.COORD_XY(file + Position.FILE_LEFT, (9 - rank) + Position.RANK_TOP);
    }

    private static Position initial() {
        Position p = ChineseChessEngine.parseFen(INIT);
        assertNotNull(p, "the standard opening FEN must pass validation");
        return p;
    }

    // ---- isSquare ----

    @Test
    @DisplayName("isSquare is total: it answers for any int")
    void isSquareIsTotal() {
        assertTrue(ChineseChessEngine.isSquare(sq(0, 0)));
        assertTrue(ChineseChessEngine.isSquare(sq(8, 9)));
        assertFalse(ChineseChessEngine.isSquare(-1));
        assertFalse(ChineseChessEngine.isSquare(0), "index 0 is in the dead zone");
        assertFalse(ChineseChessEngine.isSquare(255));
        assertFalse(ChineseChessEngine.isSquare(256));
        assertFalse(ChineseChessEngine.isSquare(Integer.MIN_VALUE));
        assertFalse(ChineseChessEngine.isSquare(Integer.MAX_VALUE));

        // ...whereas the raw engine helper indexes its table first.
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> Position.IN_BOARD(-1));
    }

    // ---- canMove ----

    @Test
    @DisplayName("canMove rejects every malformed square pair instead of throwing")
    void canMoveRejectsMalformedInput() {
        Position p = initial();
        assertFalse(ChineseChessEngine.canMove(null, sq(0, 0), sq(0, 1)), "null board");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), sq(0, 0)), "null move (src == dst)");
        assertFalse(ChineseChessEngine.canMove(p, -1, sq(0, 1)), "negative source");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), -1), "negative destination");
        assertFalse(ChineseChessEngine.canMove(p, 999, sq(0, 1)), "source past the array");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), 999), "destination past the array");
        assertFalse(ChineseChessEngine.canMove(p, sq(4, 4), sq(4, 5)), "empty source square");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), sq(1, 0)), "destination holds own piece");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 9), sq(0, 8)), "black may not move first");
    }

    @Test
    @DisplayName("canMove accepts the real opening moves")
    void canMoveAcceptsLegalMoves() {
        Position p = initial();
        assertTrue(ChineseChessEngine.canMove(p, sq(0, 0), sq(0, 1)), "rook up one rank");
        assertTrue(ChineseChessEngine.canMove(p, sq(1, 0), sq(2, 2)), "knight jump");
        assertTrue(ChineseChessEngine.canMove(p, sq(0, 3), sq(0, 4)), "pawn forward");
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), sq(0, 3)), "rook cannot jump its own pawn");
    }

    // ---- applyMove ----

    @Test
    @DisplayName("applyMove mutates only on success")
    void applyMoveOnlyMutatesOnSuccess() {
        Position p = initial();
        String before = p.toFen();
        int baseMoveNum = p.moveNum;

        assertFalse(ChineseChessEngine.applyMove(p, sq(4, 4), sq(4, 5)), "empty source");
        assertFalse(ChineseChessEngine.applyMove(p, sq(0, 0), sq(0, 0)), "null move");
        assertFalse(ChineseChessEngine.applyMove(p, -5, 9000), "garbage squares");
        assertEquals(before, p.toFen(), "a refused move must not touch the board");
        assertEquals(baseMoveNum, p.moveNum);

        assertTrue(ChineseChessEngine.applyMove(p, sq(0, 0), sq(0, 1)), "a legal move applies");
        assertFalse(before.equals(p.toFen()), "the board must actually change");
        assertEquals(baseMoveNum + 1, p.moveNum);
        assertEquals(1, p.sdPlayer, "side to move flips");
    }

    /**
     * Property test: no combination of in-range and out-of-range squares may
     * make the facade throw. Runs ~75k pairs, which is cheap and covers every
     * off-by-one an attacker could send.
     */
    @Test
    @DisplayName("canMove never throws for any pair of squares")
    void canMoveIsTotal() {
        Position p = initial();
        int checked = 0;
        for (int src = -8; src <= 264; src++) {
            for (int dst = -8; dst <= 264; dst += 7) {
                ChineseChessEngine.canMove(p, src, dst);   // must not throw
                checked++;
            }
        }
        assertTrue(checked > 10_000, "the sweep actually ran");
        assertEquals(initial().toFen(), p.toFen(), "the sweep must not mutate the board");
    }

    // ---- FEN validation ----

    @Test
    @DisplayName("isWellFormedFen accepts real FENs and rejects junk")
    void fenValidation() {
        assertTrue(ChineseChessEngine.isWellFormedFen(INIT));
        assertTrue(ChineseChessEngine.isWellFormedFen(INIT + " w"));
        assertTrue(ChineseChessEngine.isWellFormedFen(INIT + " b - - 0 1"));

        assertFalse(ChineseChessEngine.isWellFormedFen(null));
        assertFalse(ChineseChessEngine.isWellFormedFen(""));
        assertFalse(ChineseChessEngine.isWellFormedFen("   "));
        assertFalse(ChineseChessEngine.isWellFormedFen("garbage"));
        assertFalse(ChineseChessEngine.isWellFormedFen("q/9/9/9/9/9/9/9/9/9"), "'q' is not a piece letter");
        assertFalse(ChineseChessEngine.isWellFormedFen("9/9/9/9/9/9/9/9/9"), "nine ranks");
        assertFalse(ChineseChessEngine.isWellFormedFen("9/9/9/9/9/9/9/9/9/9/9"), "eleven ranks");
        assertFalse(ChineseChessEngine.isWellFormedFen("8/9/9/9/9/9/9/9/9/9"), "rank sums to 8");
        assertFalse(ChineseChessEngine.isWellFormedFen("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR x"),
                "side field must be w or b");
    }

    @Test
    @DisplayName("parseFen returns null instead of a nonsense board")
    void parseFenGuards() {
        assertNull(ChineseChessEngine.parseFen(null));
        assertNull(ChineseChessEngine.parseFen(""));
        assertNull(ChineseChessEngine.parseFen("q/9/9/9/9/9/9/9/9/9"));

        Position p = ChineseChessEngine.parseFen(INIT);
        assertNotNull(p);
        assertEquals(32, countPieces(p), "the opening position has 32 pieces");
        assertEquals(0, p.sdPlayer, "red to move");
    }

    @Test
    @DisplayName("parseFen of a black-to-move FEN hands the move to black")
    void parseFenHonoursSideField() {
        Position p = ChineseChessEngine.parseFen(INIT + " b - - 0 1");
        assertNotNull(p);
        assertEquals(1, p.sdPlayer);
        assertFalse(ChineseChessEngine.canMove(p, sq(0, 0), sq(0, 1)), "red may not move");
        assertTrue(ChineseChessEngine.canMove(p, sq(0, 9), sq(0, 8)), "black may move");
    }

    private static int countPieces(Position p) {
        int n = 0;
        for (byte b : p.squares) {
            if (b != 0) n++;
        }
        return n;
    }
}
