package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.xqwlight.Position;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人机对局(PVC)的规则契约。
 *
 * <p>0.1.2 之前 {@link BoardMode#PVC} 是个死枚举:{@code /qisheng mode pvc} 只把
 * 一个没人读的标记改掉,棋盘上不会出现电脑对手。这里锁死补上之后的三条底线:
 * <ol>
 *   <li>电脑只在 PVC + 对局中 + 它那一侧确实空着时才走子;</li>
 *   <li>电脑永远不能替人类走子,也不能自己跟自己下;</li>
 *   <li>电脑走子走的是和人类完全同一条校验路径,盘面不会因此损坏。</li>
 * </ol>
 *
 * <p>不覆盖的部分(需要真的服务器,单元测试做不到):搜索跑在哪个线程、回复的
 * 延迟、以及 {@code PvcController} 的调度去重 —— 这些在
 * {@link PvcController} 的注释里说明。
 */
@Timeout(60)
class PvcGameLoopTest {

    static {
        // ResourceKey (and therefore BoardKey) reaches BuiltInRegistries, which
        // refuses to run before the bootstrap and throws
        // "IllegalArgumentException: Not bootstrapped (called from registry
        // ResourceKey[minecraft:root / minecraft:root])". The tests that only
        // touch game rules do not need it, but the class holds one static
        // dimension key, so pay for it once here.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A dimension key, built by hand rather than through
     * {@code net.minecraft.core.registries.Registries} — the class-level
     * bootstrap above is enough for a plain registry key, and picking the
     * dimension registry out of {@code Registries} would drag in the whole
     * vanilla registry set.
     */
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            ResourceKey.createRegistryKey(new ResourceLocation("minecraft", "dimension")),
            new ResourceLocation("minecraft", "overworld"));

    private static final String INIT_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";

    /** A PVC game where the human sat down as red and the computer owns black. */
    private static GameSession humanVsComputer(UUID human) {
        GameSession s = new GameSession();
        s.setMode(BoardMode.PVC);
        s.setRedPlayer(human);
        s.setState(GameState.PLAYING);
        return s;
    }

    /** A legal (src, dst) pair for whichever side is to move, or null when mated. */
    private static int[] anyLegalMove(GameSession s) {
        int mv = ChineseChessEngine.firstLegalMove(s.getChessData().toFen());
        return mv <= 0 ? null : new int[] {Position.SRC(mv), Position.DST(mv)};
    }

    // ---- whose turn the computer thinks it is ----

    @Test
    @DisplayName("电脑只在 PVC 且轮到自己那一侧时才该走子")
    void computerOnlyMovesOnItsOwnTurnInPvc() {
        UUID human = UUID.randomUUID();
        GameSession s = humanVsComputer(human);

        assertEquals(0, s.getSdPlayer());
        assertFalse(s.isComputerToMove(), "红方是人类,不是电脑的回合");

        s.setSdPlayer(1);
        assertTrue(s.isComputerToMove(), "黑方空着且轮到黑方 = 电脑的回合");
    }

    @Test
    @DisplayName("PVP 模式下空座位不等于电脑:对手离线不该让服务端替他走棋")
    void pvpModeNeverHandsTheBoardToTheComputer() {
        UUID red = UUID.randomUUID();
        GameSession s = new GameSession();
        s.setMode(BoardMode.PVP);
        s.setRedPlayer(red);
        s.setState(GameState.PLAYING);
        s.setSdPlayer(1);

        assertNull(s.getBlackPlayer());
        assertFalse(s.isComputerToMove());
    }

    @Test
    @DisplayName("两边都没人就没人走子 —— 电脑不允许自己跟自己下")
    void computerDoesNotPlayBothSides() {
        GameSession s = new GameSession();
        s.setMode(BoardMode.PVC);
        s.setState(GameState.PLAYING);
        s.setSdPlayer(0);

        assertFalse(s.isComputerToMove());
    }

    @Test
    @DisplayName("对局结束后电脑不再走子")
    void finishedGameEndsTheComputerTurn() {
        GameSession s = humanVsComputer(UUID.randomUUID());
        s.setSdPlayer(1);
        assertTrue(s.isComputerToMove());

        s.setState(GameState.FINISHED);
        assertFalse(s.isComputerToMove());
    }

    // ---- engine moves go through the same gate as human moves ----

    @Test
    @DisplayName("一整个回合:人类走红,电脑走黑,盘面与走子方都对得上")
    void humanAndComputerCompleteOneFullTurn() {
        UUID human = UUID.randomUUID();
        GameSession s = humanVsComputer(human);

        int[] red = anyLegalMove(s);
        assertNotNull(red, "开局红方必须有合法着法");
        String fenBefore = s.getChessData().toFen();

        assertEquals(GameLogic.SelectOutcome.OK, GameLogic.trySelect(s, human, red[0]));
        assertEquals(GameLogic.MoveOutcome.OK, GameLogic.tryMove(s, human, red[0], red[1]));
        assertEquals(1, s.getSdPlayer(), "红方走完轮到黑方");
        assertNotEquals(fenBefore, s.getChessData().toFen());
        assertTrue(s.isComputerToMove());

        int[] black = anyLegalMove(s);
        assertNotNull(black, "黑方必须有合法着法");
        assertEquals(GameLogic.MoveOutcome.OK, GameLogic.tryEngineMove(s, black[0], black[1]));
        assertEquals(0, s.getSdPlayer(), "黑方走完回到红方");
        assertFalse(s.isComputerToMove());
    }

    @Test
    @DisplayName("轮不到电脑时 tryEngineMove 拒绝执行,且一个字节都不改盘面")
    void engineCannotMoveForTheHuman() {
        UUID human = UUID.randomUUID();
        GameSession s = humanVsComputer(human);

        int[] legal = anyLegalMove(s);
        assertNotNull(legal);
        String fenBefore = s.getChessData().toFen();

        assertEquals(GameLogic.MoveOutcome.NOT_YOUR_TURN,
                GameLogic.tryEngineMove(s, legal[0], legal[1]));
        assertEquals(fenBefore, s.getChessData().toFen());
        assertEquals(0, s.getSdPlayer());
    }

    @Test
    @DisplayName("电脑走非法着法只会被拒绝,不会污染盘面")
    void engineIllegalMoveLeavesTheBoardUntouched() {
        UUID human = UUID.randomUUID();
        GameSession s = humanVsComputer(human);
        s.setSdPlayer(1);
        String fenBefore = s.getChessData().toFen();

        // A real square pair that is simply not a move (src == dst).
        int corner = Position.COORD_XY(Position.FILE_LEFT, Position.RANK_TOP);
        assertEquals(GameLogic.MoveOutcome.ILLEGAL_MOVE, GameLogic.tryEngineMove(s, corner, corner));
        // Off-board squares are a distinct outcome — isSquare() runs before the
        // engine's unchecked IN_BOARD lookup, so this must not throw either.
        assertEquals(GameLogic.MoveOutcome.OUT_OF_BOUNDS, GameLogic.tryEngineMove(s, -5, 300));
        assertEquals(fenBefore, s.getChessData().toFen());
        assertEquals(1, s.getSdPlayer());
    }

    @Test
    @DisplayName("对局结束后 tryEngineMove 返回 GAME_FINISHED")
    void engineStopsAfterGameOver() {
        UUID human = UUID.randomUUID();
        GameSession s = humanVsComputer(human);
        s.setSdPlayer(1);
        int[] legal = anyLegalMove(s);
        assertNotNull(legal);

        s.setState(GameState.FINISHED);
        assertEquals(GameLogic.MoveOutcome.GAME_FINISHED,
                GameLogic.tryEngineMove(s, legal[0], legal[1]));
    }

    // ---- what PvcController asks of the engine facade ----

    @Test
    @DisplayName("searchBestMove 返回一个盘面真的接受的着法")
    void searchReturnsAPlayableMove() {
        int mv = ChineseChessEngine.searchBestMove(INIT_FEN, 64, 200);
        assertTrue(mv > 0, "开局总该有一步可走");

        Position pos = ChineseChessEngine.parseFen(INIT_FEN);
        assertNotNull(pos);
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)),
                "搜出来的着法必须过 canMove");
    }

    @Test
    @DisplayName("FEN 坏掉时搜索与兜底都不抛异常,只返回 0(调度方会跳过这手)")
    void unusableFenDegradesToZero() {
        assertEquals(0, ChineseChessEngine.searchBestMove("not a fen", 64, 50));
        assertEquals(0, ChineseChessEngine.searchBestMove(null, 64, 50));
        assertEquals(0, ChineseChessEngine.searchBestMove("", 64, 50));
        assertEquals(0, ChineseChessEngine.firstLegalMove(INIT_FEN.substring(0, 5)));
        assertEquals(0, ChineseChessEngine.firstLegalMove(null));
    }

    @Test
    @DisplayName("firstLegalMove 是合法的兜底着法,不是随便一个数")
    void fallbackMoveIsPlayable() {
        int mv = ChineseChessEngine.firstLegalMove(INIT_FEN);
        assertTrue(mv > 0);

        Position pos = ChineseChessEngine.parseFen(INIT_FEN);
        assertNotNull(pos);
        assertTrue(ChineseChessEngine.canMove(pos, Position.SRC(mv), Position.DST(mv)));
    }

    // ---- SessionManager's side of the mode ----

    @Test
    @DisplayName("PVC 下一个人落座就开局:没有第二个人要等")
    void pvcJoinStartsTheGameImmediately() {
        SessionManager sm = SessionManager.get();
        sm.resetAll();
        sm.setGlobalMode(BoardMode.PVC);

        UUID human = UUID.randomUUID();
        BoardKey key = BoardKey.of(DIMENSION, new BlockPos(11, 64, 11));
        GameSession s = sm.getOrCreate(key);
        try {
            assertEquals(BoardMode.PVC, s.getMode());
            assertTrue(sm.joinGame(human, key));

            assertEquals(GameState.PLAYING, s.getState());
            assertEquals(human, s.getRedPlayer());
            assertNull(s.getBlackPlayer(), "黑方座位留给电脑,不该被任何人占");
            assertEquals(0, s.getSdPlayer(), "人类执红先行");
            assertFalse(s.isComputerToMove());
        } finally {
            sm.resetAll();
        }
    }

    @Test
    @DisplayName("PVC 下第二个真人不能顶替电脑的座位")
    void pvcBoardRefusesASecondHuman() {
        SessionManager sm = SessionManager.get();
        sm.resetAll();
        sm.setGlobalMode(BoardMode.PVC);

        UUID human = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        BoardKey key = BoardKey.of(DIMENSION, new BlockPos(12, 64, 12));
        GameSession s = sm.getOrCreate(key);
        try {
            assertTrue(sm.joinGame(human, key));
            assertFalse(sm.joinGame(intruder, key));
            assertFalse(sm.takeOver(intruder, key, 1));
            assertNull(s.getBlackPlayer());
        } finally {
            sm.resetAll();
        }
    }

    @Test
    @DisplayName("PVP 行为没被改动:第一个人坐下后仍然等第二个人")
    void pvpJoinStillWaitsForTheOpponent() {
        SessionManager sm = SessionManager.get();
        sm.resetAll();

        UUID red = UUID.randomUUID();
        UUID black = UUID.randomUUID();
        BoardKey key = BoardKey.of(DIMENSION, new BlockPos(13, 64, 13));
        GameSession s = sm.getOrCreate(key);
        try {
            assertEquals(BoardMode.PVP, s.getMode());
            assertTrue(sm.joinGame(red, key));
            assertEquals(GameState.WAITING, s.getState(), "还差一个人,不该开局");

            assertTrue(sm.joinGame(black, key));
            assertEquals(GameState.PLAYING, s.getState());
            assertEquals(black, s.getBlackPlayer());
        } finally {
            sm.resetAll();
        }
    }

    @Test
    @DisplayName("全局模式只影响新建棋盘,不改动已有对局")
    void modeOnlyAppliesToNewBoards() {
        SessionManager sm = SessionManager.get();
        sm.resetAll();

        BoardKey existing = BoardKey.of(DIMENSION, new BlockPos(14, 64, 14));
        GameSession before = sm.getOrCreate(existing);
        try {
            assertEquals(BoardMode.PVP, before.getMode());

            sm.setGlobalMode(BoardMode.PVC);
            assertEquals(BoardMode.PVP, before.getMode(), "已经存在的棋盘不该被改规则");

            BoardKey later = BoardKey.of(DIMENSION, new BlockPos(15, 64, 15));
            assertEquals(BoardMode.PVC, sm.getOrCreate(later).getMode());
        } finally {
            sm.resetAll();
        }
    }
}
