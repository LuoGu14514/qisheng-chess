package com.qisheng.chess.client;

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
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

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
    private static final int CELL_MIN = 24;
    private static final int CELL_MAX = 80;
    private static final int PADDING = 24;
    private static final int TITLE_H = 24;
    private static final int STATUS_H = 18;
    private static final int HINT_H = 16;
    private static final int FOOTER_RESERVED = TITLE_H + STATUS_H + HINT_H + 16;

    private static final int LEFT_W = 150;
    private static final int RIGHT_W = 160;
    private static final int CHAT_H = 120;

    private static final int ACTION_SELECT = 1;
    private static final int ACTION_MOVE   = 2;

    private static final int COL_BG_DIM        = 0xCC1A1A1A;
    private static final int COL_BOARD_FRAME   = 0xFF6B4226;
    private static final int COL_BOARD_BG      = 0xFFE8C788;
    private static final int COL_GRID          = 0xFF4A2810;
    private static final int COL_RIVER_TEXT    = 0xFF4A2810;
    private static final int COL_RED_FILL      = 0xFFE85D5D;
    private static final int COL_RED_RING      = 0xFF8C1F1F;
    private static final int COL_BLACK_FILL    = 0xFF2C2C2C;
    private static final int COL_BLACK_RING    = 0xFF000000;
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

    private String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
    private int sdPlayer = 0;
    private int stateOrd = 1;
    private int selectedSq = -1;

    private Roster roster;

    private int cell = 48;
    private int boardX = 0;
    private int boardY = 0;
    private int boardW = 0;
    private int boardH = 0;

    private SpectatorListWidget specList;
    private PlayerBadgeWidget redBadge;
    private PlayerBadgeWidget blackBadge;
    private ActionButtonsWidget actionPanel;
    private ChatBoxWidget chatBox;

    public CChessBoardScreen(BlockPos boardPos, UUID selfId,
                             String fen, int sdPlayer, int stateOrd,
                             int selectPoint, int myRole) {
        super(Component.literal("启升棋"));
        this.boardPos = boardPos;
        this.selfId = selfId;
        if (fen != null) this.fen = fen;
        this.sdPlayer = sdPlayer;
        this.stateOrd = stateOrd;
        this.selectedSq = selectPoint;
        this.myRole = myRole;
        this.viewerIsBlack = (myRole == 1);
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
        if (fen != null) this.fen = fen;
        this.sdPlayer = sdPlayer;
        this.stateOrd = stateOrd;
        this.selectedSq = selectPoint;
        rebuildActionPanel();
    }

    public void onDrawInvite(UUID from) {
        String name = lookupName(from);
        Component text = (name == null || name.isEmpty())
                ? Component.literal("对方申请求和")
                : Component.literal(name + " 申请求和");
        ActionPopup.show(text, PopupS2CPacket.Severity.WARN,
                "接受", "拒绝",
                () -> sendDrawResponse(true),
                () -> sendDrawResponse(false));
    }

    public void onDrawResult(DrawPackets.Result res) {
        String msg = switch (res) {
            case REQUESTED -> "求和申请已发出,等待对方回应…";
            case ACCEPTED  -> "对方接受求和!";
            case REJECTED  -> "对方拒绝求和。";
            case CANCELLED -> "求和申请已撤销。";
        };
        PopupOverlay.show(Component.literal(msg), PopupS2CPacket.Severity.INFO, 3);
    }

    public void onSwitchInvite(UUID from) {
        String name = lookupName(from);
        Component text = Component.literal((name == null || name.isEmpty() ? "对方" : name)
                + " 想跟你换身份(红/黑互换)");
        ActionPopup.show(text, PopupS2CPacket.Severity.WARN,
                "接受", "拒绝",
                () -> sendSwitchResponse(true),
                () -> sendSwitchResponse(false));
    }

    public void onSwitchResult(SwitchPackets.Result res) {
        String msg = switch (res) {
            case ACCEPTED        -> "换身份成功!";
            case REJECTED        -> "对方拒绝换身份。";
            case CANCELLED       -> "换身份请求已撤销。";
            case NO_LONGER_VALID -> "换身份失败(对方已离线)。";
        };
        PopupOverlay.show(Component.literal(msg), PopupS2CPacket.Severity.INFO, 3);
    }

    public void onChatMessage(UUID senderId, String text) {
        if (chatBox == null) return;
        chatBox.pushMessage(senderId, lookupName(senderId), text);
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
        int specW = LEFT_W - 2 * PADDING;
        if (specW < 80) specW = 80;
        int specH = boardH + 2 * 16;
        this.specList = new SpectatorListWidget(
                (this.width - LEFT_W) / 2,
                boardY - 8,
                specW, specH);
        addRenderableWidget(this.specList);

        int badgeH = 40;
        int redX = boardX - 4 - 130;
        int redY = boardY + boardH - badgeH + 4;
        int blackX = boardX + boardW + 4;
        int blackY = boardY + boardH - badgeH + 4;
        redBadge = new PlayerBadgeWidget(redX, redY, 130, badgeH, null, null, 0, myRole == 0);
        blackBadge = new PlayerBadgeWidget(blackX, blackY, 130, badgeH, null, null, 1, myRole == 1);
        addRenderableWidget(redBadge);
        addRenderableWidget(blackBadge);

        int actionX = boardX + boardW + 4;
        int actionY = boardY;
        int actionW = 130;
        int actionH = 5 * 24;
        actionPanel = new ActionButtonsWidget(actionX, actionY, actionW, actionH);
        addRenderableWidget(actionPanel);
        rebuildActionPanel();

        int chatX = boardX + boardW + 4;
        int chatY = boardY + boardH + 8;
        int chatW = 200;
        chatBox = new ChatBoxWidget(chatX, chatY, chatW, CHAT_H);
        addRenderableWidget(chatBox);
    }

    @Override
    public void resize(net.minecraft.client.Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        recomputeLayout();
    }

    private void recomputeLayout() {
        int boardAreaW = Math.max(1, this.width - LEFT_W - RIGHT_W - 2 * PADDING);
        int boardAreaH = Math.max(1, this.height - FOOTER_RESERVED - PADDING - CHAT_H);
        int rawCell = Math.min(boardAreaW / (COLS - 1), boardAreaH / (ROWS - 1));
        this.cell = Math.max(CELL_MIN, Math.min(CELL_MAX, rawCell));
        this.boardW = (COLS - 1) * this.cell;
        this.boardH = (ROWS - 1) * this.cell;
        int boardAreaX = LEFT_W + (boardAreaW - this.boardW) / 2;
        this.boardX = boardAreaX + PADDING;
        this.boardY = PADDING + TITLE_H;
    }

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
                    "求和", ActionButtonsWidget.Kind.NEUTRAL, true, this::onClickDraw));
            acts.add(new ActionButtonsWidget.Action(
                    "认输", ActionButtonsWidget.Kind.DANGER, true, this::onClickResign));
        }
        if (amSpec && inGame) {
            if (redEmpty) acts.add(new ActionButtonsWidget.Action(
                    "切换到红方", ActionButtonsWidget.Kind.PRIMARY, true,
                    () -> onClickTakeOver(0)));
            if (blackEmpty) acts.add(new ActionButtonsWidget.Action(
                    "切换到黑方", ActionButtonsWidget.Kind.PRIMARY, true,
                    () -> onClickTakeOver(1)));
            if (!redEmpty) acts.add(new ActionButtonsWidget.Action(
                    "申请跟红方换", ActionButtonsWidget.Kind.NEUTRAL, true,
                    () -> onClickRequestSwitch(0)));
            if (!blackEmpty) acts.add(new ActionButtonsWidget.Action(
                    "申请跟黑方换", ActionButtonsWidget.Kind.NEUTRAL, true,
                    () -> onClickRequestSwitch(1)));
        }
        acts.add(new ActionButtonsWidget.Action(
                "评论/聊天", ActionButtonsWidget.Kind.NEUTRAL, true, this::onClickComment));
        actionPanel.setActions(acts);
    }

    private void onClickDraw() {
        FriendlyByteBuf buf = DrawPackets.Request.write();
        NetworkManager.sendToServer(ModNetwork.CHESS_DRAW_REQUEST, buf);
    }

    private void onClickResign() {
        ActionPopup.show(Component.literal("确定认输?"),
                PopupS2CPacket.Severity.ERROR,
                "认输", "取消",
                () -> {
                    FriendlyByteBuf buf = ChessResignC2SPacket.write();
                    NetworkManager.sendToServer(ModNetwork.CHESS_RESIGN, buf);
                },
                () -> {});
    }

    private void onClickTakeOver(int role) {
        PopupOverlay.show(Component.literal(
                "请右键棋盘方块(站位需要 ≤5 格)即可以 "
                        + (role == 0 ? "红" : "黑") + " 方接手。"),
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
        PopupOverlay.show(Component.literal("点聊天框输入 — 回车发送"),
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

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        PopupOverlay.tick();
        ActionPopup.tick();
        gfx.fill(0, 0, this.width, this.height, COL_BG_DIM);

        Position pos = Position.fromFenString(fen);

        drawFrame(gfx);
        drawGrid(gfx);
        drawRiver(gfx);
        drawPalace(gfx);
        if (pos != null) {
            drawPieces(gfx, pos);
            if (selectedSq >= 0 && pos.squares[selectedSq] != 0) {
                drawSelection(gfx, selectedSq);
                drawLegalDots(gfx, pos, selectedSq);
            }
        }
        drawTitle(gfx);
        drawStatus(gfx);
        drawHint(gfx);

        super.render(gfx, mouseX, mouseY, partialTick);

        PopupOverlay.render(gfx, this.width);
        ActionPopup.render(gfx, this.width);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (chatBox != null && chatBox.isInputFocused()) {
            if (keyCode == 257) {
                String t = chatBox.consumeInput();
                if (t != null && !t.isEmpty()) {
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
        String left = "楚 河";
        String right = "汉 界";
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

        String letter = switch (base) {
            case 0 -> isRed ? "帅" : "将";
            case 1 -> isRed ? "仕" : "士";
            case 2 -> isRed ? "相" : "象";
            case 3 -> "马";
            case 4 -> "车";
            case 5 -> "炮";
            case 6 -> isRed ? "兵" : "卒";
            default -> "?";
        };
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

    private void drawLegalDots(GuiGraphics gfx, Position pos, int selectedSq) {
        int dotR = Math.max(3, cell / 7);
        for (int file = 0; file < COLS; file++) {
            for (int rank = 0; rank < ROWS; rank++) {
                int dst = Position.COORD_XY(file + Position.FILE_LEFT,
                                            rank + Position.RANK_TOP);
                if (dst == selectedSq) continue;
                int mv = Position.MOVE(selectedSq, dst);
                if (!pos.legalMove(mv)) continue;
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
            case 0 -> "红方(先手)";
            case 1 -> "黑方(后手)";
            default -> "旁观";
        };
        String turnStr = sdPlayer == 0 ? "红方回合" : "黑方回合";
        String stateStr = switch (stateOrd) {
            case 0 -> "等待对手";
            case 1 -> "对局中";
            case 2 -> "已结束";
            default -> "未知";
        };
        String title = "启升棋  ·  " + roleStr + " ·  " + turnStr + " ·  " + stateStr;
        int tw = this.font.width(title);
        gfx.drawString(this.font, title, (this.width - tw) / 2, PADDING, COL_TEXT_PRIMARY);
    }

    private void drawStatus(GuiGraphics gfx) {
        int y = boardY + boardH + 24;
        String msg;
        int color;
        if (stateOrd == 2) {
            msg = "对局已结束  ·  按 ESC 关闭";
            color = COL_TEXT_MUTED;
        } else if (myRole < 0) {
            msg = "旁观模式 — 只读";
            color = COL_TEXT_MUTED;
        } else if (myRole != sdPlayer) {
            String waiting = sdPlayer == 0 ? "红方" : "黑方";
            msg = "等待 " + waiting + " 落子…";
            color = COL_TEXT_MUTED;
        } else {
            msg = "点己方棋子查看走位  ·  再点取消  ·  点蓝点 / 红环 = 走子";
            color = COL_TEXT_PRIMARY;
        }
        int mw = this.font.width(msg);
        gfx.drawString(this.font, msg, (this.width - mw) / 2, y, color);
    }

    private void drawHint(GuiGraphics gfx) {
        int y = boardY + boardH + 24 + STATUS_H;
        String h = "@ " + boardPos.toShortString() + "  ·  按 ESC 关闭";
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

    private int viewFile(int fenFile) { return viewerIsBlack ? (COLS - 1 - fenFile) : fenFile; }
    private int viewRank(int fenRank) { return viewerIsBlack ? (ROWS - 1 - fenRank) : fenRank; }
    private int viewCX(int fenFile) { return squareX(viewFile(fenFile)); }
    private int viewCY(int fenRank) { return squareY(viewRank(fenRank)); }
    private int fenFileFromView(int viewFile) { return viewerIsBlack ? (COLS - 1 - viewFile) : viewFile; }
    private int fenRankFromView(int viewRank) { return viewerIsBlack ? (ROWS - 1 - viewRank) : viewRank; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (ActionPopup.mouseClicked(mouseX, mouseY, button)) return true;
        if (PopupOverlay.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int dx = (int) Math.round(mouseX) - boardX;
        int dy = (int) Math.round(mouseY) - boardY;
        if (dx >= 0 && dx <= boardW && dy >= 0 && dy <= boardH) {
            int viewFile = Math.round(dx / (float) cell);
            int viewRank = Math.round(dy / (float) cell);
            if (viewFile >= 0 && viewFile < COLS && viewRank >= 0 && viewRank < ROWS) {
                int fenFile = fenFileFromView(viewFile);
                int fenRank = fenRankFromView(viewRank);
                int sq = Position.COORD_XY(fenFile + Position.FILE_LEFT,
                                            fenRank + Position.RANK_TOP);
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

        Position pos = Position.fromFenString(fen);
        byte pc = (pos != null) ? pos.squares[sq] : 0;

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
        if (selectedSq >= 0 && pos != null) {
            int mv = Position.MOVE(selectedSq, sq);
            if (pos.legalMove(mv)) {
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