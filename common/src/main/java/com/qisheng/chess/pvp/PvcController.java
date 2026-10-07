package com.qisheng.chess.pvp;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Computer opponent for {@link BoardMode#PVC} boards.
 *
 * <h2>How a computer move happens</h2>
 * <ol>
 *   <li>Some state change is broadcast through
 *       {@link GameBroadcaster#broadcastSync}, which hands the session to
 *       {@link #maybeSchedule}. That single choke point is enough because the
 *       mod already routes <em>every</em> board mutation through it — the
 *       packet handlers, the commands, the join path and the disconnect
 *       handler all end up here.</li>
 *   <li>{@link GameSession#isComputerToMove()} decides whether the move is the
 *       computer's: PVC mode, game running, and nobody seated on the side to
 *       move.</li>
 *   <li>The search itself runs on a <b>worker thread</b>, not on the tick
 *       thread — a few hundred milliseconds of search inside a packet handler
 *       would be a server freeze. Only the FEN crosses that boundary; the
 *       search parses it onto a private board owned by the variant.</li>
 *   <li>The result comes back through {@code MinecraftServer#execute}, and
 *       everything is re-derived there: the level, the session, and whether it
 *       is still the computer's turn. A human who resigned, disconnected, or
 *       was reset in the meantime simply makes the reply a no-op.</li>
 * </ol>
 *
 * <h2>Variant-aware (v0.3.1)</h2>
 * The board may now be xiangqi <em>or</em> international. Both flow through the
 * same scheduler; the variant decides what move encoding and what engine to use.
 * International chess has no alpha-beta engine yet ({@code searchBestMove} is
 * a thin wrapper over {@code firstLegalMove}) but the surrounding plumbing is
 * identical.
 *
 * <h2>Why the reply is delayed</h2>
 * The search usually finishes in well under {@link #MIN_REPLY_DELAY_MILLIS}, so
 * without a floor the board would visibly change twice in the same frame and
 * the player would never see their own move. A short pause also bounds how much
 * CPU one board can consume per second.
 */
public final class PvcController {

    private static final Logger LOG = LoggerFactory.getLogger("qisheng_chess");

    private PvcController() {}

    /** Time budget for one computer move. */
    private static final int THINK_MILLIS = 600;

    /** Wall-clock floor between the human's move and the computer's reply. */
    private static final long MIN_REPLY_DELAY_MILLIS = 400L;

    /** Wall-clock floor for fast variants (gomoku / go / international). */
    private static final long FAST_REPLY_DELAY_MILLIS = 120L;

    /**
     * Per-variant think budget. Xiangqi really uses the full 600 ms; the
     * other variants resolve in <1 ms so we hand them a tighter cap to
     * keep the worker snappy.
     */
    private static int thinkMillisFor(BoardVariant variant) {
        return "xiangqi".equals(variant.id()) ? THINK_MILLIS : 80;
    }

    /**
     * Per-variant reply delay. Xiangqi feels right with 400 ms; trivial
     * games finish so fast that 400 ms feels sluggish, so we shrink the
     * floor.
     */
    private static long replyDelayFor(BoardVariant variant) {
        return "xiangqi".equals(variant.id()) ? MIN_REPLY_DELAY_MILLIS : FAST_REPLY_DELAY_MILLIS;
    }

    /**
     * How long an in-flight marker may live before it is considered lost.
     *
     * <p>The marker is cleared by the reply itself. It exists to stop
     * {@link #maybeSchedule} from queueing a second search for the same board
     * when a broadcast re-enters it. If the server stops before the reply runs,
     * no one clears it — hence the expiry, after which the board is simply
     * allowed to be scheduled again.
     */
    private static final long IN_FLIGHT_TIMEOUT_NANOS = 30_000_000_000L;

    /** Boards with a computer move in flight: {@link BoardKey} → schedule time. */
    private static final Map<BoardKey, Long> IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * One search at a time, for the whole server.
     *
     * <p>Serializing is intentional: a search is CPU-bound, and an unbounded
     * pool would let a handful of PVC boards saturate every core. The cost is
     * that two boards finish their moves in sequence rather than in parallel —
     * invisible at {@value #THINK_MILLIS} ms per move.
     */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "qisheng-chess-engine");
        t.setDaemon(true);
        // Below normal priority: the engine must never outrank the tick loop.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /**
     * Start a computer move for {@code session} if it is the computer's turn.
     *
     * <p>Safe to call on any state change, and safe to call when it is not the
     * computer's turn — that is the common case and costs one boolean.
     */
    public static void maybeSchedule(ServerLevel level, BlockPos pos, GameSession session) {
        if (level == null || pos == null || session == null) return;
        if (!session.isComputerToMove()) return;

        BoardKey key = BoardKey.of(level, pos);
        long now = System.nanoTime();
        Long previous = IN_FLIGHT.get(key);
        if (previous != null) {
            if (now - previous < IN_FLIGHT_TIMEOUT_NANOS) return; // already on its way
            LOG.warn("[qisheng] 棋盘 {} 的上一手电脑着法未落子(超过 {} 秒),重新调度。",
                    key.describe(), IN_FLIGHT_TIMEOUT_NANOS / 1_000_000_000L);
        }
        IN_FLIGHT.put(key, now);

        MinecraftServer server = level.getServer();
        if (server == null) {
            IN_FLIGHT.remove(key);
            return;
        }

        // Only the FEN crosses the thread boundary. Everything else — the
        // level, the session, whose turn it is, the variant — is re-derived
        // on the tick thread when the answer comes back.
        BoardVariant variant = BoardRegistry.getByIdOrDefault(session.getVariantId());
        BoardState state = session.getBoardState();
        String fen = variant.toFen(state);
        try {
            WORKER.execute(() -> searchThenPlay(server, key, fen, session.getVariantId()));
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.remove(key);
            LOG.warn("[qisheng] 棋局引擎线程已关闭,跳过 {} 的电脑着法。", key.describe());
        }
    }

    /** Worker thread: search, hold the reply back to a human pace, hand off to the server. */
    private static void searchThenPlay(MinecraftServer server, BoardKey key, String fen, String variantId) {
        BoardVariant variant = BoardRegistry.getByIdOrDefault(variantId);
        long start = System.nanoTime();
        int thinkMillis = thinkMillisFor(variant);
        long replyDelay = replyDelayFor(variant);
        Move move = Move.NONE;
        try {
            move = variant.searchBestMove(fen, /*depth=*/19, thinkMillis);
        } catch (RuntimeException e) {
            // An engine crash must not kill the worker thread: the next board
            // still needs it. The fallback below keeps this board playable.
            LOG.error("[qisheng] 电脑搜索在 {} 处抛出异常:{}", key.describe(), e.toString(), e);
        }
        if (move.src() < 0 || move.dst() < 0) {
            LOG.warn("[qisheng] 引擎在 {} 处没有给出着法,回退到第一个合法着法。", key.describe());
            try {
                move = variant.firstLegalMove(fen);
            } catch (RuntimeException e) {
                LOG.error("[qisheng] 兜底着法在 {} 处抛出异常:{}", key.describe(), e.toString(), e);
                move = Move.NONE;
            }
        }

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        long remaining = replyDelay - elapsedMillis;
        if (remaining > 0) {
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Move finalMove = move;
        try {
            server.execute(() -> play(server, key, finalMove, variantId));
        } catch (RuntimeException e) {
            IN_FLIGHT.remove(key);
            LOG.warn("[qisheng] 无法把电脑着法交回主线程({})。", key.describe(), e);
        }
    }

    /** Tick thread: apply the move if it is still the computer's turn. */
    private static void play(MinecraftServer server, BoardKey key, Move move, String variantId) {
        try {
            IN_FLIGHT.remove(key);

            ServerLevel level = SessionManager.resolve(server, key, null);
            GameSession session = SessionManager.get().get(key);
            if (level == null || session == null) return;
            // The human may have resigned, disconnected, switched the board to
            // PVP, or been reset while the search was running.
            if (!session.isComputerToMove()) return;

            // The variant may have changed mid-search (e.g. setMode); use the
            // session's current variant, not the one captured when the search
            // started.
            BoardVariant currentVariant = BoardRegistry.getByIdOrDefault(session.getVariantId());
            if (!session.getVariantId().equals(variantId)) {
                // Variant flip: this board now plays a different game, and the
                // move we searched for may no longer make sense. Just re-broadcast
                // the new state and let the next turn pick the right engine.
                GameBroadcaster.broadcastSync(level, session, key.pos());
                return;
            }

            BlockPos pos = key.pos();
            GameLogic.MoveOutcome out = apply(session, move);
            if (out != GameLogic.MoveOutcome.OK) {
                // The engine handed back something this board rejects. Play the
                // weakest legal move instead of leaving the game waiting on a
                // turn nobody will take.
                Move fallback = currentVariant.firstLegalMove(currentVariant.toFen(session.getBoardState()));
                if (fallback.src() >= 0) {
                    LOG.warn("[qisheng] 电脑着法 {} 在 {} 处被拒绝({}),改用兜底着法 {}。",
                            describe(move), key.describe(), out, describe(fallback));
                    out = apply(session, fallback);
                }
            }

            if (out != GameLogic.MoveOutcome.OK) {
                // Nothing legal to play at all: the side to move is mated (a
                // save file that was edited while it was the computer's turn is
                // the only way to get here). Finish the game rather than
                // strand the board.
                GameResult result = session.getSdPlayer() == 0 ? GameResult.BLACK_WIN : GameResult.RED_WIN;
                LOG.error("[qisheng] 电脑在 {} 处无着可走({}),判定对局结束:{}。",
                        key.describe(), out, result);
                session.setResult(result);
                session.setState(GameState.FINISHED);
                GameBroadcaster.broadcastGameOver(level, session, pos, result);
                return;
            }

            GameResult result = session.getResult();
            if (result != GameResult.ONGOING) {
                GameBroadcaster.broadcastGameOver(level, session, pos, result);
            } else {
                GameBroadcaster.broadcastSync(level, session, pos);
            }
        } catch (Throwable t) {
            IN_FLIGHT.remove(key);
            LOG.error("[qisheng] 电脑落子失败({})。", key.describe(), t);
        }
    }

    private static GameLogic.MoveOutcome apply(GameSession session, Move move) {
        if (move.src() < 0 || move.dst() < 0) return GameLogic.MoveOutcome.ILLEGAL_MOVE;
        // Placement variants (gomoku + go) return Move(sq, sq) from the
        // engine — the human sent ACTION_PLACE and got a single square back,
        // so the engine's reply is also single-square. Route through the
        // placement variant of the rules; canPlace()/applyPlace() do the
        // legality and mutation, no SOURCE_MISCAME gate needed.
        return tryEngine(session, move.src(), move.dst());
    }

    /** Dispatch placement vs move engines without duplicating the seat / state gates. */
    private static GameLogic.MoveOutcome tryEngine(GameSession session, int src, int dst) {
        if (BoardRegistry.getByIdOrDefault(session.getVariantId()).isPlacement()) {
            return GameLogic.tryEnginePlace(session, dst);
        }
        return GameLogic.tryEngineMove(session, src, dst);
    }

    private static String describe(Move move) {
        if (move.src() < 0 || move.dst() < 0) return "NONE";
        return move.src() + "→" + move.dst();
    }
}