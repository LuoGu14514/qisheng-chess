package com.qisheng.chess.client;

import com.qisheng.chess.engine.BoardRegistry;
import com.qisheng.chess.engine.BoardState;
import com.qisheng.chess.engine.BoardVariant;
import com.qisheng.chess.engine.ChineseChessEngine;
import com.qisheng.chess.engine.gomoku.GomokuBoard;
import com.qisheng.chess.engine.go.GoBoard;
import com.qisheng.chess.engine.xqwlight.Position;
import com.qisheng.chess.network.ChatPackets;
import com.qisheng.chess.network.ChessResignC2SPacket;
import com.qisheng.chess.network.DrawPackets;
import com.qisheng.chess.network.ModNetwork;
import com.qisheng.chess.network.PopupS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket;
import com.qisheng.chess.network.SpectatorListS2CPacket.Roster;
import com.qisheng.chess.network.SwitchPackets;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-side GUI chess board (v2, second batch).
 *
 * <h2>Layout</h2>
 * <pre>
 *   [PopupOverlay stack]    (text-only popups)
 *   [ActionPopup stack]     (draw / switch invites with buttons)
 *
 *   ┌──────────────┐  ┌────────────────────┐  ┌──────────────┐
 *   │ Spectator    │  │ Title              │  │ Action       │
 *   │ list         │  ├────────────────────┤  │ buttons      │
 *   │ (left, scroll│  │                    │  │ (right)      │
 *   │  -able)      │  │   CHESS BOARD      │  │              │
 *   │              │  │                    │  │ 求和          │
 *   │              │  │                    │  │ 认输          │
 *   │              │  │                    │  │ 切换到红方    │
 *   │              │  ├────────────────────┤  │ 切换到黑方    │
 *   │              │  │ [Red badge]  [Black│  │ 评论/聊天     │
 *   └──────────────┘  └────────────────────┘  └──────────────┘
 *                              │
 *                       [Chat box]
 * </pre>
 */
public class CChessBoardScreen extends Screen {

    private static final int COLS = 9;
    private static final int ROWS = 10;
    private static final int CELL_MIN = 32;
    private static final int CELL_MAX = 80;

    /** Three-column layout: left panel | board | right panel. */
    private static final int PADDING = 12;
    private static final int TITLE_H = 28;
    private static final int STATUS_H = 24;
    private static final int LEFT_W  = 170;
    private static final int RIGHT_W = 210;

    /** Per-widget sizes inside their panels. */
    private static final int BADGE_H   = 44;
    private static final int BADGE_GAP = 6;
    private static final int BTN_H     = 24;
    private static final int BTN_GAP   = 4;
    private static final int CHAT_ROWS = 8;

    private static final int ACTION_SELECT = 1;
    private static final int ACTION_MOVE   = 2;

    /** Sentinel square index used by Go to represent a pass (no stone placed). */
    private static final int PASS_SQ = -1;

    private static final int COL_BG_DIM        = 0xCC1A1A1A;
    private static final int COL_BOARD_FRAME   = 0xFF6B4226;
    private static final int COL_BOARD_BG      = 0xFFE8C788;
    private static final int COL_GRID          = 0xFF4A2810;
    private static final int COL_GRID_DARK     = 0xFF333333;
    private static final int COL_GO_BG         = 0xFFDBA664;
    private static final int COL_GO_STAR       = 0xFF000000;
    private static final int COL_GOMOKU_BG     = 0xFFE8C788;
    private static final int COL_RIVER_TEXT    = 0xFF4A2810;
    private static final int COL_RED_FILL      = 0xFFE85D5D;
    private static final int COL_RED_RING      = 0xFF8C1F1F;
    private static final int COL_BLACK_FILL    = 0xFF2C2C2C;
    private static final int COL_BLACK_RING    = 0xFF000000;
    private static final int COL_WHITE_STONE   = 0xFFF5F5F5;
    private static final int COL_BLACK_STONE   = 0xFF101010;
    private static final int COL_STONE_OUTLINE = 0xFF606060;
    private static final int COL_INNER_RING    = 0xFFD4A857;
    private static final int COL_RED_TEXT      = 0xFFFFF8DC;
    private static final int COL_BLACK_TEXT    = 0xFFFFD700;
    private static final int COL_SEL_BORDER    = 0xFFFFFF00;
    private static final int COL_LEGAL_DOT     = 0xCC4A8FD0;
    private static final int COL_CAPTURE_RING  = 0xCCFF5A4A;
    private static final int COL_TEXT_PRIMARY  = 0xFFFFFFFF;
    private static final int COL_TEXT_MUTED    = 0xFFAAAAAA;
    private static final int COL_TEXT_DIM      = 0xFF777777;

    private final BlockPos boardPos;
    @SuppressWarnings("unused")
    private final UUID selfId;
    private final int myRole;
    private final boolean viewerIsBlack;
    /**
     * Variant id from {@code BoardRegistry}. Default {@code "xiangqi"}. When
     * {@code "international"}, the GUI shows a "GUI 待 v0.3.2" placeholder
     * instead of the 9x10 xiangqi board (only the server-side engine and
     * session state are implemented in v0.3.1).
     */
    private String variantId = "xiangqi";
    /**
     * XOR of {@link #viewerIsBlack} (role-based: red player sees red at the
     * bottom, black player the reverse) and the board-block's facing flip
     * (a south-facing board is mounted with its back to a north wall and is
     * always shown rotated 180°). Used by the {@code viewFile/viewRank/...}
     * helpers; if you flip this without going through {@link #setBoardFlipped}
     * the cached {@code boardFlipped} will be wrong.
     */
    private boolean boardFlipped;

    private String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
    private int sdPlayer = 0;
    private int stateOrd = 1;
    private int selectedSq = -1;
    /**
     * Last successful move src & destination (or {@code -1} if no move has been
     * played yet). Updated on every {@code applySync}; the renderer uses
     * {@link #drawLastMoveOverlay} to paint a translucent ring around both
     * squares so the player can see at a glance where the game is at.
     */
    private int lastMoveSrc = -1;
    private int lastMoveDst = -1;

    private Roster roster;

    /**
     * Screen-owned chat log. The chat widget renders this very list, so the
     * history survives {@link #init()} (window resize) instead of dying with
     * the widget instance.
     */
    private final List<ChatBoxWidget.Message> chatLog = new ArrayList<>();
    /** Half-typed chat line, saved before a resize rebuilds the widget. */
    private String chatDraft = "";

    /** Per-screen popup stacks — see PopupOverlay / ActionPopup. */
    final PopupOverlay popups = new PopupOverlay();
    final ActionPopup actionPopup = new ActionPopup();

    // ---- 棋盘 / 合法落点缓存（P2-1）----------------------------------------
    // 改前：render() 每帧调用 Position.fromFenString(fen)（一次完整 FEN 解析
    // 加一个约 3.3 KB 的新 Position），drawLegalDots() 再对 90 个格子逐个调用
    // pos.legalMove(...)（每次都会生成整盘走法、试走、撤销），60 FPS 下每秒
    // 上千次走法生成、约 200 KB/s 垃圾。
    // 改后：两者都只在 (fen, selectedSq) 真正变化时算一次，其余帧只查表；凡是
    // 给 fen / selectedSq 赋值的地方都在赋值后调用 refreshBoardCaches()。
    // 两个缓存的键同时做兜底校验：万一将来漏调刷新，也不会画出过期落点。
    private String cachedFen;
    /**
     * Variant-aware cached board. For xiangqi we keep the legacy
     * {@link Position} reference so the existing draw path can keep using
     * {@code pos.squares[sq]} directly; for other variants we hold a
     * {@link BoardState} (gomoku {@link GomokuBoard}, go {@link GoBoard},
     * international chess board) and read piece bytes through the variant's
     * {@code pieceAt}. Only one of the two is non-null for any given frame.
     */
    private Position cachedPos;
    private BoardState cachedBoardState;
    private BoardVariant cachedVariant;
    private String destCacheFen;
    private int destCacheSelect = Integer.MIN_VALUE;
    private boolean[] destCache;
    /**
     * Legal destinations for the current selection as reported by the server.
     * {@code null} when no bitmap has been received yet, when no piece is
     * selected, or when the selection does not belong to the side to move.
     * When non-null, {@link #legalDestinations()} prefers it over the local
     * cached compute — the server is the single source of truth, the client
     * no longer runs {@code Position#legalMove} 90 times per frame.
     */
    private boolean[] serverDests;
    /** FEN the {@link #serverDests} bitmap was computed against, for staleness check. */
    private String serverDestsFen;
    /** Selection the {@link #serverDests} bitmap was computed for. */
    private int serverDestsSelect = Integer.MIN_VALUE;

    private int cell = 48;
    private int boardX = 0;
    private int boardY = 0;
    private int boardW = 0;
    private int boardH = 0;

    // Cached layout rects (set in recomputeLayout, applied in applyLayout):
    //   specList / redBadge / blackBadge   — left panel
    //   actionPanel / chatBox              — right panel
    private Rect specRect;
    private Rect redBadgeRect;
    private Rect blackBadgeRect;
    private Rect actionRect;
    private Rect chatRect;

    private record Rect(int x, int y, int w, int h) {}

    private SpectatorListWidget specList;
    private PlayerBadgeWidget redBadge;
    private PlayerBadgeWidget blackBadge;
    private ActionButtonsWidget actionPanel;
    private ChatBoxWidget chatBox;

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole) {
        this(boardPos, selfId, fen, sdPlayer, stateOrd, selectPoint, myRole, null, -1, -1, false);
    }

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole, boolean[] legalDests) {
        this(boardPos, selfId, fen, sdPlayer, stateOrd, selectPoint, myRole, legalDests, -1, -1, false);
    }

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole, boolean[] legalDests,
                             boolean flipped) {
        this(boardPos, selfId, fen, sdPlayer, stateOrd, selectPoint, myRole, legalDests, -1, -1, flipped);
    }

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole, boolean[] legalDests,
                             int lastMoveSrc, int lastMoveDst,
                             boolean flipped) {
        this(boardPos, selfId, fen, "xiangqi", sdPlayer, stateOrd, selectPoint, myRole,
                legalDests, lastMoveSrc, lastMoveDst, flipped);
    }

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, String variantId, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole, boolean[] legalDests,
                             int lastMoveSrc, int lastMoveDst,
                             boolean flipped) {
        super(Component.translatable("qisheng.chess.screen.title"));
        this.boardPos = boardPos;
        this.selfId = selfId;
        if (fen != null) this.fen = fen;
        this.variantId = (variantId == null || variantId.isEmpty()) ? "xiangqi" : variantId;
        this.sdPlayer = sdPlayer;
        this.stateOrd = stateOrd;
        this.selectedSq = selectPoint;
        this.myRole = myRole;
        this.viewerIsBlack = (myRole == 1);
        this.boardFlipped = this.viewerIsBlack ^ flipped;
        this.lastMoveSrc = ChineseChessEngine.isSquare(lastMoveSrc) ? lastMoveSrc : -1;
        this.lastMoveDst = ChineseChessEngine.isSquare(lastMoveDst) ? lastMoveDst : -1;
        // 打开棋盘界面（开局 / 重开）时 fen 与 selectedSq 在这里被赋值，
        // 之后同样要作废缓存，让首次绘制按当前状态重建。
        refreshBoardCaches();
        applyServerDests(legalDests);
    }

    public BlockPos getBoardPos() { return boardPos; }

    public void applyRoster(Roster r) {
        this.roster = r;
        if (specList != null) specList.applyRoster(r);
        if (redBadge != null) {
            UUID redId = r.red == null ? null : r.red.id;
            String redName = r.red == null ? null : r.red.name;
            redBadge.update(redId, redName);
        }
        if (blackBadge != null) {
            UUID blackId = r.black == null ? null : r.black.id;
            String blackName = r.black == null ? null : r.black.name;
            blackBadge.update(blackId, blackName);
        }
        rebuildActionPanel();
    }

    public void applySync(String fen, int sdPlayer, int stateOrd, int selectPoint) {
        applySync(fen, sdPlayer, stateOrd, selectPoint, null, -1, -1);
    }

    public void applySync(String fen, int sdPlayer, int stateOrd, int selectPoint,
                          boolean[] legalDests) {
        applySync(fen, sdPlayer, stateOrd, selectPoint, legalDests, -1, -1);
    }

    public void applySync(String fen, int sdPlayer, int stateOrd, int selectPoint,
                          boolean[] legalDests, int lastMoveSrc, int lastMoveDest) {
        applySync(fen, sdPlayer, stateOrd, selectPoint, legalDests, lastMoveSrc, lastMoveDest, this.variantId);
    }

    public void applySync(String fen, int sdPlayer, int stateOrd, int selectPoint,
                          boolean[] legalDests, int lastMoveSrc, int lastMoveDest,
                          String variantId) {
        if (fen != null) this.fen = fen;
        if (variantId != null && !variantId.isEmpty()) this.variantId = variantId;
        this.sdPlayer = sdPlayer;
        this.stateOrd = stateOrd;
        this.selectedSq = selectPoint;
        int prevSrc = this.lastMoveSrc;
        int prevDst = this.lastMoveDst;
        this.lastMoveSrc = ChineseChessEngine.isSquare(lastMoveSrc) ? lastMoveSrc : -1;
        this.lastMoveDst = ChineseChessEngine.isSquare(lastMoveDest) ? lastMoveDest : -1;
        // 选子 / 落子 / 开局 / 重开全都由服务器同步到这里，这是运行期唯一改动
        // fen 与 selectedSq 的入口：赋值后立刻作废棋盘与落点缓存，下一帧按新
        // 状态重算一次（宁可多刷一次，也不能漏刷）。
        refreshBoardCaches();
        applyServerDests(legalDests);
        // Detect a fresh move: previous src/dst were either none or two different
        // squares, now they're set to a new src pair. The move-sound is played here
        // (and only here) so it cannot fire twice for the same move and cannot
        // miss a move that came in via SYNC.
        if (this.lastMoveSrc >= 0 && this.lastMoveDst >= 0
                && (this.lastMoveSrc != prevSrc || this.lastMoveDst != prevDst)) {
            playMoveSound();
        }
        rebuildActionPanel();
    }

    /** Store a server-supplied legal-destinations bitmap, with a no-op key check. */
    private void applyServerDests(boolean[] legalDests) {
        this.serverDests = legalDests;
        this.serverDestsFen = (legalDests == null) ? null : this.fen;
        this.serverDestsSelect = (legalDests == null) ? Integer.MIN_VALUE : this.selectedSq;
    }

    public void onDrawInvite(UUID from) {
        String name = lookupName(from);
        Component text = (name == null || name.isEmpty())
                ? Component.translatable("qisheng.chess.draw.invite.anonymous")
                : Component.translatable("qisheng.chess.draw.invite.named",
                        TextSanitizer.strip(name));
        actionPopup.show(ActionPopup.Tag.DRAW_INVITE, text, PopupS2CPacket.Severity.WARN,
                Component.translatable("qisheng.chess.popup.accept").getString(),
                Component.translatable("qisheng.chess.popup.reject").getString(),
                () -> sendDrawResponse(true),
                () -> sendDrawResponse(false));
    }

    public void onDrawResult(DrawPackets.Result res) {
        Component msg = switch (res) {
            case REQUESTED -> Component.translatable("qisheng.chess.draw.requested");
            case ACCEPTED  -> Component.translatable("qisheng.chess.draw.accepted");
            case REJECTED  -> Component.translatable("qisheng.chess.draw.rejected");
            case CANCELLED -> Component.translatable("qisheng.chess.draw.cancelled");
        };
        // The invitation is settled (accepted / rejected / cancelled by the
        // server): retire its buttons so they cannot respond to stale state.
        actionPopup.dismiss(ActionPopup.Tag.DRAW_INVITE);
        popups.push(msg, PopupS2CPacket.Severity.INFO, 3);
    }

    public void onSwitchInvite(UUID from) {
        String name = lookupName(from);
        String who = (name == null || name.isEmpty())
                ? Component.translatable("qisheng.chess.player.opponent").getString()
                : TextSanitizer.strip(name);
        Component text = Component.translatable("qisheng.chess.switch.invite", who);
        actionPopup.show(ActionPopup.Tag.SWITCH_INVITE, text, PopupS2CPacket.Severity.WARN,
                Component.translatable("qisheng.chess.popup.accept").getString(),
                Component.translatable("qisheng.chess.popup.reject").getString(),
                () -> sendSwitchResponse(true),
                () -> sendSwitchResponse(false));
    }

    public void onSwitchResult(SwitchPackets.Result res) {
        Component msg = switch (res) {
            case ACCEPTED        -> Component.translatable("qisheng.chess.switch.accepted");
            case REJECTED        -> Component.translatable("qisheng.chess.switch.rejected");
            case CANCELLED       -> Component.translatable("qisheng.chess.switch.cancelled");
            case NO_LONGER_VALID -> Component.translatable("qisheng.chess.switch.failed_offline");
        };
        // See onDrawResult: the request is over, the buttons must go.
        actionPopup.dismiss(ActionPopup.Tag.SWITCH_INVITE);
        popups.push(msg, PopupS2CPacket.Severity.INFO, 3);
    }

    public void onChatMessage(UUID senderId, String text) {
        // Screen-owned log: the widget renders this list directly, so history
        // survives a resize even when the widget itself is rebuilt.
        ChatBoxWidget.append(chatLog,
                new ChatBoxWidget.Message(senderId, lookupName(senderId), text));
        if (chatBox != null) chatBox.onHistoryChanged();
    }

    private String lookupName(UUID id) {
        if (roster == null) return "";
        if (roster.red != null && id.equals(roster.red.id)) return roster.red.name;
        if (roster.black != null && id.equals(roster.black.id)) return roster.black.name;
        if (roster.spectators != null) {
            for (SpectatorListS2CPacket.PlayerEntry s : roster.spectators) {
                if (id.equals(s.id)) return s.name;
            }
        }
        return "";
    }

    @Override
    protected void init() {
        super.init();
        recomputeLayout();

        // Keep the half-typed chat line: the widget instance created below is
        // new, the draft lives on the Screen.
        if (this.chatBox != null) this.chatDraft = this.chatBox.getInputText();

        // Build widgets with their final cached rects. Screen.resize()
        // already re-runs init() on window resize, so the widget instances
        // get rebuilt with the new geometry automatically — everything they
        // need to remember (roster, chat log, draft) is re-applied below.
        this.specList = new SpectatorListWidget(
                specRect.x(), specRect.y(), specRect.w(), specRect.h());
        addRenderableWidget(this.specList);

        UUID redId = roster == null || roster.red == null ? null : roster.red.id;
        UUID blackId = roster == null || roster.black == null ? null : roster.black.id;
        String redName = roster == null || roster.red == null ? null : roster.red.name;
        String blackName = roster == null || roster.black == null ? null : roster.black.name;
        this.redBadge = new PlayerBadgeWidget(
                redBadgeRect.x(), redBadgeRect.y(), redBadgeRect.w(), redBadgeRect.h(),
                redId, redName, 0, myRole == 0);
        this.blackBadge = new PlayerBadgeWidget(
                blackBadgeRect.x(), blackBadgeRect.y(), blackBadgeRect.w(), blackBadgeRect.h(),
                blackId, blackName, 1, myRole == 1);
        addRenderableWidget(this.redBadge);
        addRenderableWidget(this.blackBadge);

        this.actionPanel = new ActionButtonsWidget(
                actionRect.x(), actionRect.y(), actionRect.w(), actionRect.h());
        addRenderableWidget(this.actionPanel);
        rebuildActionPanel();

        this.chatBox = new ChatBoxWidget(
                chatRect.x(), chatRect.y(), chatRect.w(), chatRect.h(), chatLog);
        this.chatBox.setInputText(this.chatDraft);
        addRenderableWidget(this.chatBox);

        // Re-apply the last received state: without this a window resize reset
        // both badges to "?", the spectator list to "棋局加载中…", the action
        // buttons and the chat history until the next CHESS_PLAYER_INFO /
        // CHESS_CHAT packet arrived.
        if (this.roster != null) applyRoster(this.roster);
    }

    /**
     * Screen teardown. Both popup stacks are per-screen state, but clearing
     * here is the belt-and-braces guarantee that no sticky chip and no live
     * invitation can outlive the GUI (they used to be {@code static} deques
     * that nothing ever cleared).
     */
    @Override
    public void removed() {
        popups.clear();
        actionPopup.clear();
        super.removed();
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        recomputeLayout();
    }

    /**
     * Pure-math layout pass. Computes the board rect + cached side-panel
     * rects without touching widget instances. Safe to call before any
     * widget is created; safe to call from {@link #resize}.
     */
    private void recomputeLayout() {
        // Available content area (excludes title bar + status bar).
        int availW = Math.max(1, this.width  - LEFT_W - RIGHT_W);
        int availH = Math.max(1, this.height - TITLE_H - STATUS_H);

        // ----- Center: board -----
        int boardAreaW = availW - 2 * PADDING;
        int boardAreaH = availH - 2 * PADDING;
        // 变种可走（v0.4）：棋盘格数来自 variant.boardFiles/Ranks，不再写死 9×10
        int variantCols = cols();
        int variantRows = rows();
        int rawCell = Math.min(boardAreaW / Math.max(1, variantCols - 1),
                               boardAreaH / Math.max(1, variantRows - 1));
        // 大棋盘（五子棋 15、围棋 19）需要更小的格子，避免超出窗口
        int cellCap = variantCols >= 15 ? 40 : CELL_MAX;
        this.cell   = Math.max(CELL_MIN, Math.min(cellCap, rawCell));
        this.boardW = Math.max(0, (variantCols - 1) * this.cell);
        this.boardH = Math.max(0, (variantRows - 1) * this.cell);

        int boardAreaX = LEFT_W + PADDING;
        int boardAreaY = TITLE_H + PADDING;
        this.boardX = boardAreaX + (boardAreaW - boardW) / 2;
        this.boardY = boardAreaY + (boardAreaH - boardH) / 2;

        // Vertical content band shared by both side panels.
        int contentTop    = TITLE_H + PADDING;
        int contentBottom = this.height - STATUS_H - PADDING;

        // ----- Left panel: red badge, black badge, spec list -----
        int leftX = PADDING;
        int leftW = LEFT_W - 2 * PADDING;
        if (leftW < 80) leftW = 80;
        this.redBadgeRect   = new Rect(leftX, contentTop,                        leftW, BADGE_H);
        this.blackBadgeRect = new Rect(leftX, contentTop + BADGE_H + BADGE_GAP,  leftW, BADGE_H);
        int specY = contentTop + 2 * BADGE_H + BADGE_GAP + PADDING;
        int specH = Math.max(80, contentBottom - specY);
        this.specRect = new Rect(leftX, specY, leftW, specH);

        // ----- Right panel: action buttons, chat box -----
        int rightX = LEFT_W + availW + PADDING;
        int rightW = RIGHT_W - 2 * PADDING;
        if (rightW < 120) rightW = 120;
        int maxButtons = 7;
        int actionH = maxButtons * (BTN_H + BTN_GAP);
        this.actionRect = new Rect(rightX, contentTop, rightW, actionH);
        int chatY = contentTop + actionH + PADDING;
        int chatH = Math.max(80, contentBottom - chatY);
        this.chatRect = new Rect(rightX, chatY, rightW, chatH);
    }

    // applyLayout / applyRect removed: AbstractWidget.width/height are
    // protected and not settable from a non-subclass. Screen.resize() runs
    // init() again on window changes, so widget geometry is always correct.

    private void rebuildActionPanel() {
        if (actionPanel == null) return;
        List<ActionButtonsWidget.Action> acts = new ArrayList<>();
        boolean inGame = stateOrd == 1;
        boolean amPlayer = (myRole == 0 || myRole == 1);
        boolean amSpec = (myRole == -1);
        boolean redEmpty = roster != null && roster.red == null;
        boolean blackEmpty = roster != null && roster.black == null;
        if (amPlayer && inGame) {
            acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.draw").getString(),
                    ActionButtonsWidget.Kind.NEUTRAL, this::onClickDraw));
            acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.resign").getString(),
                    ActionButtonsWidget.Kind.DANGER, this::onClickResign));
        }
        if (amSpec && inGame) {
            if (redEmpty) acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.take_over.red").getString(),
                    ActionButtonsWidget.Kind.PRIMARY,
                    () -> onClickTakeOver(0)));
            if (blackEmpty) acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.take_over.black").getString(),
                    ActionButtonsWidget.Kind.PRIMARY,
                    () -> onClickTakeOver(1)));
            if (!redEmpty) acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.request_switch.red").getString(),
                    ActionButtonsWidget.Kind.NEUTRAL,
                    () -> onClickRequestSwitch(0)));
            if (!blackEmpty) acts.add(new ActionButtonsWidget.Action(
                    Component.translatable("qisheng.chess.action.request_switch.black").getString(),
                    ActionButtonsWidget.Kind.NEUTRAL,
                    () -> onClickRequestSwitch(1)));
        }
        acts.add(new ActionButtonsWidget.Action(
                Component.translatable("qisheng.chess.action.chat").getString(),
                ActionButtonsWidget.Kind.NEUTRAL, this::onClickComment));
        actionPanel.setActions(acts);
    }

    private void onClickDraw() {
        FriendlyByteBuf buf = DrawPackets.Request.write();
        NetworkManager.sendToServer(ModNetwork.CHESS_DRAW_REQUEST, buf);
    }

    private void onClickResign() {
        actionPopup.show(ActionPopup.Tag.CONFIRM,
                Component.translatable("qisheng.chess.resign.confirm"),
                PopupS2CPacket.Severity.ERROR,
                Component.translatable("qisheng.chess.action.resign").getString(),
                Component.translatable("qisheng.chess.popup.cancel").getString(),
                () -> {
                    FriendlyByteBuf buf = ChessResignC2SPacket.write();
                    NetworkManager.sendToServer(ModNetwork.CHESS_RESIGN, buf);
                },
                () -> {});
    }

    private void onClickTakeOver(int role) {
        popups.push(Component.translatable("qisheng.chess.take_over.hint",
                        Component.translatable(role == 0
                                ? "qisheng.chess.side.red"
                                : "qisheng.chess.side.black")),
                PopupS2CPacket.Severity.INFO, 5);
    }

    private void onClickRequestSwitch(int role) {
        UUID target = null;
        if (roster != null) {
            if (role == 0 && roster.red != null) target = roster.red.id;
            if (role == 1 && roster.black != null) target = roster.black.id;
        }
        if (target == null) return;
        FriendlyByteBuf buf = SwitchPackets.Request.write(target);
        NetworkManager.sendToServer(ModNetwork.CHESS_SWITCH_REQUEST, buf);
    }

    private void onClickComment() {
        if (chatBox == null) return;
        chatBox.setFocused(true);
        popups.push(Component.translatable("qisheng.chess.chat.input_hint"),
                PopupS2CPacket.Severity.INFO, 3);
    }

    private void sendDrawResponse(boolean accept) {
        FriendlyByteBuf buf = DrawPackets.Response.write(accept);
        NetworkManager.sendToServer(ModNetwork.CHESS_DRAW_RESPONSE, buf);
    }

    private void sendSwitchResponse(boolean accept) {
        FriendlyByteBuf buf = SwitchPackets.Response.write(accept);
        NetworkManager.sendToServer(ModNetwork.CHESS_SWITCH_RESPONSE, buf);
    }

    // ---------- board caches (P2-1) ----------

    /**
     * 作废棋盘与合法落点缓存，下一次绘制时按当前 fen / selectedSq 重建一次。
     *
     * <p>凡是给 {@link #fen} 或 {@link #selectedSq} 赋值的地方，赋值之后都必须
     * 调用本方法：宁可多刷一次，也不能漏刷——漏刷会让界面画出过期的落点。
     */
    private void refreshBoardCaches() {
        cachedFen = null;
        cachedPos = null;
        cachedBoardState = null;
        cachedVariant = null;
        destCacheFen = null;
        destCacheSelect = Integer.MIN_VALUE;
        destCache = null;
        serverDests = null;
        serverDestsFen = null;
        serverDestsSelect = Integer.MIN_VALUE;
    }

    /**
     * 当前 FEN 对应的棋盘，只在 FEN 字符串变化时重新解析一次（改前 render()
     * 每帧解析，60 FPS 下每秒新建 60 个约 3.3 KB 的 Position）。
     *
     * <p>v0.4 起：变种可走接口 {@link BoardVariant#parseState}，所以非象棋的
     * 变种（五子棋、围棋、国际象棋）解析后存到 {@link #cachedBoardState}，
     * 渲染时通过 {@link BoardVariant#pieceAt} 读子力。
     */
    private BoardState boardState() {
        String f = fen == null ? "" : fen;
        BoardVariant v = variant();
        if (cachedBoardState == null || cachedVariant != v || !f.equals(cachedFen)) {
            cachedFen = f;
            cachedVariant = v;
            BoardState parsed = v.parseState(f);
            cachedBoardState = parsed;
            // 同步 cachedPos:仅当变种是象棋时才填充,旧绘制路径继续用。
            cachedPos = (parsed instanceof Position p) ? p : Position.fromFenString(f);
        }
        return cachedBoardState;
    }

    /**
     * Xiangqi-only accessor for the legacy render path. Returns the cached
     * {@link Position} for xiangqi, {@code null} for every other variant
     * (those go through {@link #boardState()} + {@link BoardVariant#pieceAt}).
     */
    private Position position() {
        boardState();
        return cachedPos;
    }

    /** Look up the variant descriptor for the current screen (never null). */
    private BoardVariant variant() {
        return BoardRegistry.getByIdOrDefault(variantId);
    }

    /** Files per rank for the active variant (xiangqi 9, chess 8, gomoku 15, go 9/19). */
    private int cols() { return variant().boardFiles(); }

    /** Ranks for the active variant (xiangqi 10, chess 8, gomoku 15, go 9/19). */
    private int rows() { return variant().boardRanks(); }

    /** True when the current variant is the default Xiangqi (uses the xiangqi render path). */
    private boolean isXiangqi() {
        return "xiangqi".equals(variantId);
    }

    /** True when the current variant is gomoku (simple grid + black/white stones). */
    private boolean isGomoku() {
        return "gomoku".equals(variantId);
    }

    /** True when the current variant is Go (9x9 or 19x19, simple grid + stones with star points). */
    private boolean isGo() {
        return "go9".equals(variantId) || "go19".equals(variantId);
    }

    /** True when the active variant accepts single-click placement (gomoku + go). */
    private boolean isPlacementVariant() {
        return isGomoku() || isGo();
    }

    /**
     * 当前选中棋子的全部合法落点，下标即棋盘内部坐标；未选中
     * （selectedSq 不在盘上）时返回全 false。
     *
     * <p>首选服务器下发的位图（v0.2 起）：服务端随 {@code CHESS_SYNC} / {@code
     * CHESS_OPEN_SCREEN} 广播合法的位图，客户端不再每帧 N 次 {@code
     * canMove}。没有下发时回退到本地计算；本地分支同样按 {@code (fen,
     * selectedSq)} 缓存，避免重算。位图长度按变种总格数对齐。
     */
    private boolean[] legalDestinations() {
        BoardVariant v = variant();
        int total = v.totalSquares();
        if (serverDests != null && serverDests.length >= total
                && serverDestsSelect == selectedSq
                && (serverDestsFen == null || serverDestsFen.equals(fen))) {
            return serverDests;
        }
        String f = fen == null ? "" : fen;
        if (destCache != null && destCache.length == total
                && destCacheSelect == selectedSq && f.equals(destCacheFen)) {
            return destCache;
        }
        boolean[] dests = new boolean[total];
        if (v.isValidSquare(selectedSq)) {
            BoardState bs = boardState();
            if (bs != null && v.pieceAt(bs, selectedSq) != 0) {
                for (int file = 0; file < v.boardFiles(); file++) {
                    for (int rank = 0; rank < v.boardRanks(); rank++) {
                        int dst = v.indexForFileRank(file, rank);
                        if (dst == selectedSq) continue;
                        if (v.canMove(bs, selectedSq, dst)) {
                            dests[dst] = true;
                        }
                    }
                }
            }
        }
        destCacheFen = f;
        destCacheSelect = selectedSq;
        destCache = dests;
        return dests;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        popups.tick();
        actionPopup.tick();
        gfx.fill(0, 0, this.width, this.height, COL_BG_DIM);

        BoardState bs = boardState();

        if (isXiangqi()) {
            drawFrame(gfx);
            drawGrid(gfx);
            drawRiver(gfx);
            drawPalace(gfx);
            // Last-move overlay goes under the pieces / selection / legal-dot
            // stack so the highlighted squares don't visually compete with the
            // current selection ring.
            drawLastMoveOverlay(gfx);
            if (bs instanceof Position pos) {
                drawPieces(gfx, pos);
                // 是否在盘上统一用门面判断：selectedSq 来自网络包，越界时
                // pos.squares[selectedSq] 会直接抛数组越界。
                if (ChineseChessEngine.isSquare(selectedSq) && pos.squares[selectedSq] != 0) {
                    drawSelection(gfx, selectedSq);
                    drawLegalDots(gfx, pos, selectedSq);
                }
            }
        } else if ("international".equals(this.variantId)) {
            drawFrame(gfx);
            drawInternationalPlaceholder(gfx);
        } else if (isGomoku()) {
            drawFrame(gfx);
            drawGomokuBoard(gfx, bs);
        } else if (isGo()) {
            drawFrame(gfx);
            drawGoBoard(gfx, bs);
        }
        drawTitle(gfx);
        drawStatus(gfx);
        drawHint(gfx);

        super.render(gfx, mouseX, mouseY, partialTick);

        // While a dialog with buttons is up, push the plain chips below it:
        // the two centre-screen stacks used to overlap, so clicking a chip
        // could hit an invitation button instead.
        int actionBottom = actionPopup.renderedBottom();
        int popupTop = actionBottom > 0 ? actionBottom + 4 : PopupOverlay.DEFAULT_TOP;
        popups.render(gfx, this.width, popupTop);
        actionPopup.render(gfx, this.width);
    }

    /**
     * Centered "国际象棋 GUI 待 v0.3.2" placeholder for the international variant.
     * v0.3.1 ships the engine + session state but does not yet render the
     * 8x8 board, so this screen is the only GUI signal the player gets that
     * the server is actually running a chess game.
     */
    private void drawInternationalPlaceholder(GuiGraphics gfx) {
        Component line1 = Component.translatable("qisheng.chess.screen.variant.international.placeholder.line1");
        Component line2 = Component.translatable("qisheng.chess.screen.variant.international.placeholder.line2");
        int cx = boardX + boardW / 2;
        int cy = boardY + boardH / 2;
        gfx.drawCenteredString(this.font, line1, cx, cy - 10, COL_TEXT_PRIMARY);
        gfx.drawCenteredString(this.font, line2, cx, cy + 10, COL_TEXT_MUTED);
    }

    /**
     * Gomoku board: simple 15×15 grid with black/white stone discs (no piece
     * labels, no river/palace). v0.4 ships a functional but minimal renderer;
     * win highlights and coordinate guides are planned for v0.4.1.
     */
    private void drawGomokuBoard(GuiGraphics gfx, BoardState bs) {
        int fx0 = boardX - 16;
        int fy0 = boardY - 16;
        gfx.fill(fx0, fy0, fx0 + boardW + 32, fy0 + boardH + 32, COL_GRID_DARK);
        gfx.fill(fx0 + 4, fy0 + 4,
                 fx0 + boardW + 28, fy0 + boardH + 28, COL_GOMOKU_BG);

        int lineThick = Math.max(1, cell / 50);
        int cMax = cols();
        int rMax = rows();
        for (int c = 0; c < cMax; c++) {
            int x = squareX(c);
            gfx.fill(x - lineThick / 2, squareY(0),
                     x + (lineThick + 1) / 2, squareY(rMax - 1) + 1, COL_GRID);
        }
        for (int r = 0; r < rMax; r++) {
            int y = squareY(r);
            gfx.fill(squareX(0), y - lineThick / 2,
                     squareX(cMax - 1) + 1, y + (lineThick + 1) / 2, COL_GRID);
        }

        if (bs == null) return;
        // Last-move ring goes under the stones so it doesn't visually compete.
        drawPlacementLastMoveOverlay(gfx);

        BoardVariant v = variant();
        int discR = Math.max(7, cell / 2 - 2);
        for (int rank = 0; rank < rMax; rank++) {
            for (int file = 0; file < cMax; file++) {
                int sq = v.indexForFileRank(file, rank);
                byte pc = v.pieceAt(bs, sq);
                if (pc == 0) continue;
                boolean isBlack = (pc == GomokuBoard.BLACK);
                drawStone(gfx, viewCX(file), viewCY(rank), discR, isBlack);
            }
        }
    }

    /**
     * Go board: 9×9 or 19×19 grid with star points and stone discs. Same
     * coordinate system as the board; the only special-case markup is the
     * star-point dots at the canonical Go positions (hoshi).
     */
    private void drawGoBoard(GuiGraphics gfx, BoardState bs) {
        int fx0 = boardX - 16;
        int fy0 = boardY - 16;
        gfx.fill(fx0, fy0, fx0 + boardW + 32, fy0 + boardH + 32, COL_GRID_DARK);
        gfx.fill(fx0 + 4, fy0 + 4,
                 fx0 + boardW + 28, fy0 + boardH + 28, COL_GO_BG);

        int cMax = cols();
        int rMax = rows();
        int lineThick = Math.max(1, cell / 50);
        for (int c = 0; c < cMax; c++) {
            int x = squareX(c);
            gfx.fill(x - lineThick / 2, squareY(0),
                     x + (lineThick + 1) / 2, squareY(rMax - 1) + 1, COL_GRID);
        }
        for (int r = 0; r < rMax; r++) {
            int y = squareY(r);
            gfx.fill(squareX(0), y - lineThick / 2,
                     squareX(cMax - 1) + 1, y + (lineThick + 1) / 2, COL_GRID);
        }
        drawStarPoints(gfx, cMax, rMax);

        if (bs == null) return;
        drawPlacementLastMoveOverlay(gfx);

        BoardVariant v = variant();
        int discR = Math.max(7, cell / 2 - 2);
        for (int rank = 0; rank < rMax; rank++) {
            for (int file = 0; file < cMax; file++) {
                int sq = v.indexForFileRank(file, rank);
                byte pc = v.pieceAt(bs, sq);
                if (pc == 0) continue;
                boolean isBlack = (pc == GoBoard.BLACK);
                drawStone(gfx, viewCX(file), viewCY(rank), discR, isBlack);
            }
        }
    }

    /**
     * Hoshi (star points) for Go. Only two sizes are wired (9×9 + 19×19);
     * any other size (custom boards, future variants) gets no star points.
     */
    private void drawStarPoints(GuiGraphics gfx, int cMax, int rMax) {
        int[][] hoshi;
        if (cMax == 9 && rMax == 9) {
            hoshi = new int[][]{{4, 4}};
        } else if (cMax == 19 && rMax == 19) {
            hoshi = new int[][]{{3, 3}, {3, 9}, {3, 15},
                                {9, 3}, {9, 9}, {9, 15},
                                {15, 3}, {15, 9}, {15, 15}};
        } else {
            return;
        }
        int dotR = Math.max(2, cell / 14);
        for (int[] p : hoshi) {
            int cx = viewCX(p[0]), cy = viewCY(p[1]);
            gfx.fill(cx - dotR, cy - dotR, cx + dotR + 1, cy + dotR + 1, COL_GO_STAR);
        }
    }

    /**
     * Translucent overlay ring for the most recent placement (gomoku + go).
     * Skipped if the move came back as a pass (sentinel -1).
     */
    private void drawPlacementLastMoveOverlay(GuiGraphics gfx) {
        drawPlacementLastMoveSquare(gfx, lastMoveSrc);
        drawPlacementLastMoveSquare(gfx, lastMoveDst);
    }

    private void drawPlacementLastMoveSquare(GuiGraphics gfx, int sq) {
        if (sq < 0 || sq >= variant().totalSquares()) return;
        int file = variant().fileOf(sq);
        int rank = variant().rankOf(sq);
        if (file < 0 || file >= cols() || rank < 0 || rank >= rows()) return;
        int pad = Math.max(3, cell / 12);
        int r = Math.max(10, cell / 2) + pad;
        int cx = viewCX(file), cy = viewCY(rank);
        gfx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, 0xC0FFEB6B);
    }

    /** Draw one black/white stone disc with a thin outline so it reads on any background. */
    private void drawStone(GuiGraphics gfx, int cx, int cy, int r, boolean isBlack) {
        int fill = isBlack ? COL_BLACK_STONE : COL_WHITE_STONE;
        gfx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, fill);
        drawRectOutline(gfx, cx - r, cy - r, 2 * r + 1, 2 * r + 1, COL_STONE_OUTLINE, 1);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (chatBox != null && chatBox.isInputFocused()) {
            if (keyCode == 257) {
                // consumeInput() already clamps; clamp once more so no path can
                // hand an over-long string to FriendlyByteBuf.writeUtf (that
                // threw EncoderException and killed the client).
                String t = ChatBoxWidget.clampToWire(chatBox.consumeInput());
                if (!t.isEmpty()) {
                    FriendlyByteBuf buf = ChatPackets.Send.write(t);
                    NetworkManager.sendToServer(ModNetwork.CHESS_CHAT, buf);
                }
                return true;
            } else if (keyCode == 259) {
                chatBox.backspace();
                return true;
            } else if (keyCode == 263) {
                chatBox.leftArrow();
                return true;
            } else if (keyCode == 262) {
                chatBox.rightArrow();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (chatBox != null && chatBox.isInputFocused()) {
            chatBox.charTyped(c);
            return true;
        }
        return super.charTyped(c, modifiers);
    }

    private void drawFrame(GuiGraphics gfx) {
        int fx0 = boardX - 16;
        int fy0 = boardY - 16;
        int fx1 = boardX + boardW + 16;
        int fy1 = boardY + boardH + 16;
        gfx.fill(fx0, fy0, fx1, fy1, COL_BOARD_FRAME);
        gfx.fill(fx0 + 4, fy0 + 4, fx1 - 4, fy1 - 4, COL_BOARD_BG);
    }

    private void drawGrid(GuiGraphics gfx) {
        int lineThick = Math.max(1, cell / 40);
        for (int c = 0; c < COLS; c++) {
            int x = squareX(c);
            gfx.fill(x - lineThick / 2, squareY(0),
                     x + (lineThick + 1) / 2, squareY(4) + 1, COL_GRID);
            gfx.fill(x - lineThick / 2, squareY(5),
                     x + (lineThick + 1) / 2, squareY(ROWS - 1) + 1, COL_GRID);
        }
        for (int r = 0; r < ROWS; r++) {
            int y = squareY(r);
            gfx.fill(boardX, y - lineThick / 2,
                     boardX + boardW + 1, y + (lineThick + 1) / 2, COL_GRID);
        }
    }

    private void drawRiver(GuiGraphics gfx) {
        int ry0 = squareY(4) + 2;
        int ry1 = squareY(5) - 2;
        int riverBg = 0xFFD9B989;
        gfx.fill(boardX, ry0, boardX + boardW, ry1, riverBg);
        String left = Component.translatable("qisheng.chess.board.river.left").getString();
        String right = Component.translatable("qisheng.chess.board.river.right").getString();
        int cy = (squareY(4) + squareY(5)) / 2 - this.font.lineHeight / 2;
        int leftX = (boardX + squareX(COLS / 2)) / 2 - this.font.width(left) / 2;
        int rightX = (squareX(COLS / 2) + boardX + boardW) / 2 - this.font.width(right) / 2;
        gfx.drawString(this.font, left, leftX, cy, COL_RIVER_TEXT);
        gfx.drawString(this.font, right, rightX, cy, COL_RIVER_TEXT);
    }

    private void drawPalace(GuiGraphics gfx) {
        drawDiagonal(gfx, squareX(3), squareY(0), squareX(5), squareY(2));
        drawDiagonal(gfx, squareX(5), squareY(0), squareX(3), squareY(2));
        drawDiagonal(gfx, squareX(3), squareY(7), squareX(5), squareY(9));
        drawDiagonal(gfx, squareX(5), squareY(7), squareX(3), squareY(9));
    }

    private void drawDiagonal(GuiGraphics gfx, int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        int x = x1, y = y1;
        while (true) {
            gfx.fill(x, y, x + 1, y + 1, COL_GRID);
            if (x == x2 && y == y2) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }

    private void drawPieces(GuiGraphics gfx, Position pos) {
        int discR = Math.max(13, cell / 2 - 2);
        for (int rank = 0; rank < ROWS; rank++) {
            for (int file = 0; file < COLS; file++) {
                int sq = Position.COORD_XY(file + Position.FILE_LEFT,
                                           rank + Position.RANK_TOP);
                byte pc = pos.squares[sq];
                if (pc == 0) continue;
                drawPiece(gfx, viewCX(file), viewCY(rank), discR, pc);
            }
        }
    }

    /**
     * 棋子字面量,按 (阵营, 棋子种类) 下标 0..6 排列,顺序与 {@code drawPiece} 里
     * 的 {@code base} 一致(0=将/帅 … 6=兵/卒)。
     *
     * <p>改造前 {@code drawPiece} 每帧对每个棋子调一次
     * {@code Component.translatable(...).getString()} —— 满盘 32 子 × 60 FPS 就是
     * 每秒近两千次翻译查找加字符串分配。现在整个屏幕只解析一次并缓存在实例上,
     * 关掉再打开棋盘(新实例)即可拿到新语言的值。
     */
    private static final String[] PIECE_KEYS_RED = {
            "qisheng.chess.piece.red_king",     "qisheng.chess.piece.red_advisor",
            "qisheng.chess.piece.red_elephant", "qisheng.chess.piece.horse",
            "qisheng.chess.piece.chariot",      "qisheng.chess.piece.cannon",
            "qisheng.chess.piece.red_soldier",
    };
    private static final String[] PIECE_KEYS_BLACK = {
            "qisheng.chess.piece.black_king",     "qisheng.chess.piece.black_advisor",
            "qisheng.chess.piece.black_elephant", "qisheng.chess.piece.horse",
            "qisheng.chess.piece.chariot",        "qisheng.chess.piece.cannon",
            "qisheng.chess.piece.black_soldier",
    };

    /** 14 格:0..6 = 红,7..13 = 黑。延迟到第一次真正画棋子时再解析。 */
    private String[] pieceLabels;

    private String pieceLabel(int base, boolean isRed) {
        if (base < 0 || base >= 7) return "?";
        if (pieceLabels == null) {
            String[] own   = isRed ? PIECE_KEYS_RED : PIECE_KEYS_BLACK;
            String[] other = isRed ? PIECE_KEYS_BLACK : PIECE_KEYS_RED;
            pieceLabels = new String[14];
            for (int i = 0; i < 7; i++) {
                pieceLabels[i]     = Component.translatable(own[i]).getString();
                pieceLabels[i + 7] = Component.translatable(other[i]).getString();
            }
        }
        return pieceLabels[isRed ? base : base + 7];
    }

    private void drawPiece(GuiGraphics gfx, int cx, int cy, int r, byte pc) {
        boolean isRed = (pc & 8) == 8;
        boolean isBlack = (pc & 16) == 16;
        if (!isRed && !isBlack) return;
        int base = isRed ? pc - 8 : pc - 16;

        int discFill = isRed ? COL_RED_FILL : COL_BLACK_FILL;
        int ringColor = isRed ? COL_RED_RING : COL_BLACK_RING;
        gfx.fill(cx - r, cy - r, cx + r, cy + r, discFill);
        drawRectOutline(gfx, cx - r, cy - r, 2 * r, 2 * r, ringColor, 2);
        int inner = Math.max(2, r - 4);
        drawRectOutline(gfx, cx - inner, cy - inner, 2 * inner, 2 * inner, COL_INNER_RING, 1);

        String letter = pieceLabel(base, isRed);
        int textColor = isRed ? COL_RED_TEXT : COL_BLACK_TEXT;
        int tw = this.font.width(letter);
        gfx.drawString(this.font, letter, cx - tw / 2, cy - this.font.lineHeight / 2, textColor);
    }

    private void drawSelection(GuiGraphics gfx, int sq) {
        int sf = (sq & 0xF) - Position.FILE_LEFT;
        int sr = ((sq >> 4) & 0xF) - Position.RANK_TOP;
        if (sf < 0 || sf >= COLS || sr < 0 || sr >= ROWS) return;
        int pad = Math.max(3, cell / 12);
        int r = Math.max(10, cell / 2) + pad;
        int cx = viewCX(sf), cy = viewCY(sr);
        drawRectOutline(gfx, cx - r, cy - r, 2 * r, 2 * r, COL_SEL_BORDER, 3);
    }

    /**
     * Paint the translucent "last move" overlay on the source and destination
     * squares. Drawn under the selection border / legal dots / pieces so it
     * doesn't fight them for visibility. Skips drawing either square if its
     * encoded {@code (file, rank)} falls off the board — this can happen for
     * a malformed packet, or when a piece moved off-board through a degenerate
     * save state.
     */
    private void drawLastMoveOverlay(GuiGraphics gfx) {
        drawLastMoveSquare(gfx, lastMoveSrc);
        drawLastMoveSquare(gfx, lastMoveDst);
    }

    private void drawLastMoveSquare(GuiGraphics gfx, int sq) {
        if (!ChineseChessEngine.isSquare(sq)) return;
        int sf = (sq & 0xF) - Position.FILE_LEFT;
        int sr = ((sq >> 4) & 0xF) - Position.RANK_TOP;
        if (sf < 0 || sf >= COLS || sr < 0 || sr >= ROWS) return;
        int pad = Math.max(3, cell / 12);
        int r = Math.max(10, cell / 2) + pad;
        int cx = viewCX(sf), cy = viewCY(sr);
        // fillArea(): same logic as drawRectOutline but with a tint that
        // reads as a translucent yellow square on top of the board bg.
        // Alpha 0xC0 + yellow 0xFFEB6B = visible but not blinding.
        int overlay = 0xC0FFEB6B;
        gfx.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, overlay);
    }

    /**
     * Play the move sound. We use {@code NoteBlock Pling} — a short, light,
     * non-disruptive chime — so it does not duplicate the louder "Block note"
     * vanilla already plays when the player themselves places a block.
     * Wrapped in a {@code Minecraft.execute} because {@link #applySync} can be
     * invoked outside the render thread.
     */
    private void playMoveSound() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            var player = mc.getSoundManager();
            if (player == null) return;
            player.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0F));
        });
    }

    private void drawLegalDots(GuiGraphics gfx, Position pos, int selectedSq) {
        // 改前每帧对 90 个格子各调用一次 pos.legalMove(...)；现在只查表，表由
        // legalDestinations() 在 (fen, selectedSq) 变化时重算一次。
        boolean[] dests = legalDestinations();
        int dotR = Math.max(3, cell / 7);
        for (int file = 0; file < COLS; file++) {
            for (int rank = 0; rank < ROWS; rank++) {
                int dst = Position.COORD_XY(file + Position.FILE_LEFT,
                                            rank + Position.RANK_TOP);
                if (!dests[dst]) continue;
                byte dstPc = pos.squares[dst];
                int cx = viewCX(file), cy = viewCY(rank);
                if (dstPc == 0) {
                    gfx.fill(cx - dotR, cy - dotR, cx + dotR + 1, cy + dotR + 1, COL_LEGAL_DOT);
                } else {
                    int ringR = Math.max(10, cell / 2) + 4;
                    drawRectOutline(gfx, cx - ringR, cy - ringR, 2 * ringR, 2 * ringR, COL_CAPTURE_RING, 2);
                }
            }
        }
    }

    private void drawTitle(GuiGraphics gfx) {
        String roleStr = switch (myRole) {
            case 0 -> Component.translatable("qisheng.chess.role.red_first").getString();
            case 1 -> Component.translatable("qisheng.chess.role.black_second").getString();
            default -> Component.translatable("qisheng.chess.role.spectator").getString();
        };
        String turnStr = sdPlayer == 0
                ? Component.translatable("qisheng.chess.turn.red").getString()
                : Component.translatable("qisheng.chess.turn.black").getString();
        String stateStr = switch (stateOrd) {
            case 0 -> Component.translatable("qisheng.chess.state.waiting").getString();
            case 1 -> Component.translatable("qisheng.chess.state.playing").getString();
            case 2 -> Component.translatable("qisheng.chess.state.finished").getString();
            default -> Component.translatable("qisheng.chess.state.unknown").getString();
        };
        String title = Component.translatable("qisheng.chess.screen.title_bar",
                roleStr, turnStr, stateStr).getString();
        int tw = this.font.width(title);
        gfx.drawString(this.font, title, (this.width - tw) / 2, PADDING, COL_TEXT_PRIMARY);
    }

    private void drawStatus(GuiGraphics gfx) {
        int y = boardY + boardH + 24;
        String msg;
        int color;
        if (stateOrd == 2) {
            msg = Component.translatable("qisheng.chess.status.finished").getString();
            color = COL_TEXT_MUTED;
        } else if (myRole < 0) {
            msg = Component.translatable("qisheng.chess.status.spectator").getString();
            color = COL_TEXT_MUTED;
        } else if (myRole != sdPlayer) {
            String waiting = sdPlayer == 0
                    ? Component.translatable("qisheng.chess.role.red").getString()
                    : Component.translatable("qisheng.chess.role.black").getString();
            msg = Component.translatable("qisheng.chess.status.waiting_move", waiting).getString();
            color = COL_TEXT_MUTED;
        } else {
            msg = Component.translatable("qisheng.chess.status.your_turn").getString();
            color = COL_TEXT_PRIMARY;
        }
        int mw = this.font.width(msg);
        gfx.drawString(this.font, msg, (this.width - mw) / 2, y, color);
    }

    private void drawHint(GuiGraphics gfx) {
        int y = boardY + boardH + 24 + STATUS_H;
        String h = Component.translatable("qisheng.chess.hint.location",
                boardPos.toShortString()).getString();
        int hw = this.font.width(h);
        gfx.drawString(this.font, h, (this.width - hw) / 2, y, COL_TEXT_DIM);
    }

    private void drawRectOutline(GuiGraphics gfx, int x, int y, int w, int h, int color, int thickness) {
        gfx.fill(x, y, x + w, y + thickness, color);
        gfx.fill(x, y + h - thickness, x + w, y + h, color);
        gfx.fill(x, y, x + thickness, y + h, color);
        gfx.fill(x + w - thickness, y, x + w, y + h, color);
    }

    private int squareX(int file) { return boardX + file * cell; }
    private int squareY(int rank) { return boardY + rank * cell; }

    private int viewFile(int fenFile) {
        int cMax = cols();
        return boardFlipped ? (cMax - 1 - fenFile) : fenFile;
    }
    private int viewRank(int fenRank) {
        int rMax = rows();
        return boardFlipped ? (rMax - 1 - fenRank) : fenRank;
    }
    private int viewCX(int fenFile) { return squareX(viewFile(fenFile)); }
    private int viewCY(int fenRank) { return squareY(viewRank(fenRank)); }
    private int fenFileFromView(int viewFile) {
        int cMax = cols();
        return boardFlipped ? (cMax - 1 - viewFile) : viewFile;
    }
    private int fenRankFromView(int viewRank) {
        int rMax = rows();
        return boardFlipped ? (rMax - 1 - viewRank) : viewRank;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (actionPopup.mouseClicked(mouseX, mouseY, button)) return true;
        if (popups.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int dx = (int) Math.round(mouseX) - boardX;
        int dy = (int) Math.round(mouseY) - boardY;
        if (dx >= 0 && dx <= boardW && dy >= 0 && dy <= boardH) {
            int viewFile = Math.round(dx / (float) cell);
            int viewRank = Math.round(dy / (float) cell);
            int cMax = cols();
            int rMax = rows();
            if (viewFile >= 0 && viewFile < cMax && viewRank >= 0 && viewRank < rMax) {
                // Playing a move releases the chat input: this branch returns
                // early, so the click never reaches ChatBoxWidget.mouseClicked
                // and the caret used to stay in the chat box while the player
                // thought they were back on the board.
                if (chatBox != null) chatBox.setFocused(false);
                int fenFile = fenFileFromView(viewFile);
                int fenRank = fenRankFromView(viewRank);
                BoardVariant v = variant();
                int sq = v.indexForFileRank(fenFile, fenRank);
                handleBoardClick(sq);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (specList != null && specList.isMouseOver(mouseX, mouseY)) {
            return specList.mouseScrolled(mouseX, mouseY, delta);
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void handleBoardClick(int sq) {
        if (stateOrd == 2) return;
        if (myRole < 0 || myRole != sdPlayer) return;
        if (!variant().isValidSquare(sq)) return;

        BoardState bs = boardState();
        BoardVariant v = variant();
        byte pc = (bs != null) ? v.pieceAt(bs, sq) : 0;

        // Placement variants (gomoku + go): a single click places a stone.
        // The server treats src == dst as a placement request (GomokuVariant
        // and GoVariant both accept that) and rejects only illegal drops
        // (occupied / suicide / ko). The client doesn't need to track a
        // separate selection state, so this whole path skips the
        // select→move dance used by xiangqi.
        if (isPlacementVariant()) {
            // Place only on an empty square; occupied squares do nothing.
            // If the player tries to play a pass for go, that comes through
            // the dedicated pass button (added below the action panel in a
            // later release).
            if (pc == 0) {
                sendInteract(ACTION_MOVE, sq, sq);
            }
            return;
        }

        // Xiangqi / international chess use the original select→move flow.
        boolean isMyPiece = pc != 0
                && ((myRole == 0 && (pc & 8) == 8)
                ||  (myRole == 1 && (pc & 16) == 16));

        if (isMyPiece) {
            if (selectedSq == sq) {
                sendInteract(ACTION_SELECT, sq, 0);
                return;
            }
            sendInteract(ACTION_SELECT, sq, 0);
            return;
        }
        if (selectedSq >= 0 && bs != null) {
            // 与落点圆点查的是同一份缓存（同样经 v.canMove 计算），所以
            // "显示为可走"和"点击被接受"永远不会互相矛盾。
            boolean[] dests = legalDestinations();
            if (v.isValidSquare(sq) && dests[sq]) {
                sendInteract(ACTION_MOVE, selectedSq, sq);
                return;
            }
        }
    }

    private void sendInteract(int action, int a, int b) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(boardPos);
        buf.writeByte(action);
        if (action == ACTION_SELECT) {
            buf.writeShort(a);
        } else if (action == ACTION_MOVE) {
            buf.writeShort(a);
            buf.writeShort(b);
        }
        NetworkManager.sendToServer(ModNetwork.CHESS_INTERACT, buf);
    }
}