package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.ChineseChessEngine;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对局持久化契约。
 *
 * <p>动机:0.1.1 里棋盘状态只活在 {@code SessionManager} 的进程内单例中,
 * 服务器一重启,存档里的棋盘就变成一句"该棋盘无效,请重新放置。"。
 * 0.1.2 起把整局快照写进 {@code CChessTileEntity} 的 NBT,这里锁死存取契约。
 *
 * <p>{@link GameSession#fromTag} 的硬性要求:**永不返回 null、永不抛异常**。
 * 它会在区块加载路径上被调用,一个手改坏的存档不该让整个世界加载失败。
 */
class GameSessionPersistenceTest {

    /**
     * 一个"下了一半"的盘面:FEN 仍然是 9 列 × 10 行、走子方为黑。
     * 这里不追求它是一局真实棋谱,只要求它是一串合法的 FEN 且与开局不同。
     */
    private static final String MID_GAME_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C4C2/9/RNBAKABNR b";

    private static CompoundTag freshTag() {
        return new GameSession().save();
    }

    @Test
    @DisplayName("全新会话往返:状态/模式/结果/走子方/选中格全部保留")
    void roundTripsFreshSession() {
        GameSession s = new GameSession();
        GameSession back = GameSession.fromTag(s.save());

        assertEquals(GameState.WAITING, back.getState());
        assertEquals(BoardMode.PVP, back.getMode());
        assertEquals(GameResult.ONGOING, back.getResult());
        assertEquals(0, back.getSdPlayer());
        assertEquals(-1, back.getSelectPoint());
        assertNull(back.getRedPlayer());
        assertNull(back.getBlackPlayer());
        assertEquals(s.getChessData().toFen(), back.getChessData().toFen());
    }

    @Test
    @DisplayName("对局中途往返:盘面、两侧玩家 UUID、走子方、选中格逐项一致")
    void roundTripsMidGameSession() {
        UUID red = UUID.randomUUID();
        UUID black = UUID.randomUUID();

        GameSession s = new GameSession();
        s.getChessData().fromFen(MID_GAME_FEN);
        s.setState(GameState.PLAYING);
        s.setMode(BoardMode.PVP);
        s.setResult(GameResult.ONGOING);
        s.setSdPlayer(1);
        s.setSelectPoint(51); // COORD_XY(3, 3) —— 红方底线左角,盘内
        s.setRedPlayer(red);
        s.setBlackPlayer(black);

        GameSession back = GameSession.fromTag(s.save());

        assertEquals(GameState.PLAYING, back.getState());
        assertEquals(1, back.getSdPlayer());
        assertEquals(51, back.getSelectPoint(), "选中格必须原样恢复,否则重启后 GUI 高亮错位");
        assertEquals(red, back.getRedPlayer());
        assertEquals(black, back.getBlackPlayer());
        assertEquals(s.getChessData().toFen(), back.getChessData().toFen());
        assertTrue(back.containsPlayer(red));
        assertTrue(back.containsPlayer(black));
        assertEquals(0, back.getPlayerRole(red));
        assertEquals(1, back.getPlayerRole(black));
    }

    @Test
    @DisplayName("已结束的对局:结果与终局盘面都要活下来")
    void roundTripsFinishedSession() {
        GameSession s = new GameSession();
        s.getChessData().fromFen(MID_GAME_FEN);
        s.setState(GameState.FINISHED);
        s.setResult(GameResult.RED_WIN);
        s.setRedPlayer(UUID.randomUUID());

        GameSession back = GameSession.fromTag(s.save());

        assertEquals(GameState.FINISHED, back.getState());
        assertEquals(GameResult.RED_WIN, back.getResult());
        assertEquals(s.getChessData().toFen(), back.getChessData().toFen());
    }

    @Test
    @DisplayName("空 tag 退化成全新对局,不抛异常")
    void emptyTagDegradesToFreshGame() {
        GameSession back = assertDoesNotThrow(() -> GameSession.fromTag(new CompoundTag()));

        assertNotNull(back);
        assertEquals(GameState.WAITING, back.getState());
        assertEquals(BoardMode.PVP, back.getMode());
        assertEquals(GameResult.ONGOING, back.getResult());
        assertEquals(0, back.getSdPlayer());
        assertEquals(-1, back.getSelectPoint());
        assertNull(back.getRedPlayer());
        assertEquals(32, countPieces(back), "应当回落到开局的 32 个子");
    }

    @Test
    @DisplayName("枚举名损坏 → 回落到默认值,不用 valueOf 抛异常")
    void unknownEnumNamesFallBack() {
        CompoundTag tag = freshTag();
        tag.putString("State", "NOT_A_STATE");
        tag.putString("Mode", "CHESS_960");
        tag.putString("Result", "");
        tag.putString("Fen", "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w");

        GameSession back = assertDoesNotThrow(() -> GameSession.fromTag(tag));
        assertEquals(GameState.WAITING, back.getState());
        assertEquals(BoardMode.PVP, back.getMode());
        assertEquals(GameResult.ONGOING, back.getResult());
    }

    @Test
    @DisplayName("损坏的 FEN 被整条忽略:不覆盖开局,更不让引擎解析出乱盘")
    void malformedFenIsIgnored() {
        // 引擎自带的 fromFen 对任何字符串都不报错 —— "garbage" 里 r/n/b/a 是合法
        // 棋子字母,所以它会安安静静地造出一盘黑子。"fromTag" 必须先自己校验。
        for (String bad : new String[]{"garbage", "", "rnbakabnr/9/1c5c1", "9/9/9/9/9/9/9/9/9/9/9"}) {
            CompoundTag tag = freshTag();
            tag.putString("Fen", bad);

            GameSession back = assertDoesNotThrow(() -> GameSession.fromTag(tag), "FEN=" + bad);
            assertEquals(32, countPieces(back), "FEN=" + bad + " 应当被拒绝并保留开局盘面");
            assertTrue(ChineseChessEngine.isWellFormedFen(back.getChessData().toFen()),
                    "FEN=" + bad + " 恢复出来的盘面必须仍然是合法 FEN");
        }
    }

    @Test
    @DisplayName("PLAYING 但两个座位都空 → 降级为 WAITING(手改存档的僵尸对局)")
    void playingWithoutAnyPlayerIsDemoted() {
        CompoundTag tag = freshTag();
        tag.putString("State", "PLAYING");

        GameSession back = GameSession.fromTag(tag);
        assertEquals(GameState.WAITING, back.getState());
    }

    @Test
    @DisplayName("PLAYING 且只有一方就座 → 保持 PLAYING(不该被误降级)")
    void playingWithOneSeatIsKept() {
        CompoundTag tag = freshTag();
        tag.putString("State", "PLAYING");
        tag.putUUID("Red", UUID.randomUUID());

        GameSession back = GameSession.fromTag(tag);
        assertEquals(GameState.PLAYING, back.getState());
    }

    @Test
    @DisplayName("选中格必须在盘内,否则回落 -1")
    void outOfBoardSelectPointBecomesMinusOne() {
        for (int bad : new int[]{9999, -5, 256, 255, 0, 1}) {
            CompoundTag tag = freshTag();
            tag.putInt("SelectPoint", bad);

            GameSession back = GameSession.fromTag(tag);
            assertEquals(-1, back.getSelectPoint(), "SelectPoint=" + bad + " 不在棋盘内,必须回落 -1");
        }
        // 一个真正在盘上的格必须被保留(51 = COORD_XY(3, 3))
        CompoundTag ok = freshTag();
        ok.putInt("SelectPoint", 51);
        assertEquals(51, GameSession.fromTag(ok).getSelectPoint());
        // 51 不是随手挑的:先确认引擎认为它在盘上,否则上面的断言等于什么都没测
        assertTrue(ChineseChessEngine.isSquare(51), "前置条件:51 必须是引擎认可的盘内格");
    }

    @Test
    @DisplayName("旁观名单与待处理邀请有意不持久化(只在线时有意义)")
    void spectatorsAndPendingOffersAreNotPersisted() {
        GameSession s = new GameSession();
        UUID spectator = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        s.addSpectator(spectator);
        s.setPendingDrawFrom(requester);

        GameSession back = GameSession.fromTag(s.save());

        assertTrue(s.isSpectator(spectator), "前置条件:内存里确实有旁观者");
        assertTrue(back.getSpectators().isEmpty(), "旁观名单不该进存档");
        assertNull(back.getPendingDrawFrom(), "待处理求和不该进存档");
        assertNull(back.getPendingSwitch());
    }

    @Test
    @DisplayName("二次往返稳定:save(fromTag(save(s))) 与 save(s) 逐字段相同")
    void roundTripIsStableAcrossGenerations() {
        GameSession s = new GameSession();
        s.getChessData().fromFen(MID_GAME_FEN);
        s.setState(GameState.PLAYING);
        s.setSdPlayer(1);
        s.setSelectPoint(52);
        s.setRedPlayer(UUID.randomUUID());
        s.setBlackPlayer(UUID.randomUUID());
        // 最近一步也要参加二次往返测试 —— 0.2.1 起新增的字段,
        // 必须跟 SelectPoint 一样走"写 → 读 → 再写"路径无丢失。
        s.setLastMoveSource(51);
        s.setLastMoveDest(52);

        CompoundTag first = s.save();
        CompoundTag second = GameSession.fromTag(first).save();

        assertTagsEqual(first, second);
        assertEquals(51, GameSession.fromTag(first).getLastMoveSource());
        assertEquals(52, GameSession.fromTag(first).getLastMoveDest());
    }

    @Test
    @DisplayName("最近一步持久化:save → fromTag 后 src/dst 保持;越界值回落 -1")
    void lastMoveRoundTrip() {
        GameSession s = new GameSession();
        s.getChessData().fromFen(MID_GAME_FEN);
        s.setLastMoveSource(51);  // COORD_XY(3, 3)
        s.setLastMoveDest(52);    // COORD_XY(4, 3)

        GameSession back = GameSession.fromTag(s.save());

        assertEquals(51, back.getLastMoveSource());
        assertEquals(52, back.getLastMoveDest());

        // 越界值必须被剔除 —— 客户端会按 isSquare() 兜底过滤,但 NBT
        // 这一层也要干净,避免把过期数据继续往下传。
        CompoundTag tag = s.save();
        tag.putInt("LastSrc", 9999);
        tag.putInt("LastDst", -2);
        GameSession bad = GameSession.fromTag(tag);
        assertEquals(-1, bad.getLastMoveSource());
        assertEquals(-1, bad.getLastMoveDest());

        // 全新会话默认就是 -1。
        GameSession fresh = GameSession.fromTag(new GameSession().save());
        assertEquals(-1, fresh.getLastMoveSource());
        assertEquals(-1, fresh.getLastMoveDest());
    }

    @Test
    @DisplayName("破坏边的显式契约:fromTag 在任何输入下都返回非 null")
    void fromTagNeverReturnsNull() {
        CompoundTag[] inputs = {
                new CompoundTag(),
                freshTag(),
                tagWithJunkTypes(),
        };
        for (CompoundTag tag : inputs) {
            assertNotNull(GameSession.fromTag(tag));
        }
    }

    /** 所有已知字段都写成了错误的 NBT 类型 —— 读的时候必须各自拿到默认值。 */
    private static CompoundTag tagWithJunkTypes() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Fen", 7);
        tag.putInt("State", 3);
        tag.putBoolean("Mode", true);
        tag.putString("Result", "RED_WIN");
        tag.putString("SdPlayer", "1");
        tag.putString("SelectPoint", "abc");
        tag.putString("Red", "not-a-uuid");
        return tag;
    }

    // ---- helpers ----

    private static int countPieces(GameSession s) {
        int n = 0;
        for (int sq = 0; sq < 256; sq++) {
            if (s.getChessData().squares[sq] != 0) n++;
        }
        return n;
    }

    private static void assertTagsEqual(CompoundTag a, CompoundTag b) {
        assertEquals(a.getString("Fen"), b.getString("Fen"), "Fen");
        assertEquals(a.getString("State"), b.getString("State"), "State");
        assertEquals(a.getString("Mode"), b.getString("Mode"), "Mode");
        assertEquals(a.getString("Result"), b.getString("Result"), "Result");
        assertEquals(a.getInt("SdPlayer"), b.getInt("SdPlayer"), "SdPlayer");
        assertEquals(a.getInt("SelectPoint"), b.getInt("SelectPoint"), "SelectPoint");
        assertEquals(a.getInt("LastSrc"), b.getInt("LastSrc"), "LastSrc");
        assertEquals(a.getInt("LastDst"), b.getInt("LastDst"), "LastDst");
        assertEquals(a.hasUUID("Red"), b.hasUUID("Red"), "Red present");
        assertEquals(a.hasUUID("Black"), b.hasUUID("Black"), "Black present");
        if (a.hasUUID("Red")) assertEquals(a.getUUID("Red"), b.getUUID("Red"), "Red");
        if (a.hasUUID("Black")) assertEquals(a.getUUID("Black"), b.getUUID("Black"), "Black");
        // 存进去的键总数也必须一致 —— 防止某次改动漏写或多写字段。
        assertEquals(a.getAllKeys(), b.getAllKeys(), "NBT 键集合");
        assertFalse(a.getAllKeys().isEmpty());
    }
}
