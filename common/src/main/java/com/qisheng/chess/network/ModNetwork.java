package com.qisheng.chess.network;

import com.qisheng.chess.QishengChess;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包注册中心
 *
 * 包清单:
 *  - CHESS_INTERACT      (C2S)  玩家在 GUI 里点选 / 走子
 *  - CHESS_SYNC          (S2C)  服务端广播棋盘状态
 *  - CHESS_OPEN_SCREEN   (S2C)  服务端告诉客户端打开棋盘 GUI
 *  - CHESS_POPUP         (S2C)  中文提示通过 in-GUI 弹窗显示
 *  - CHESS_PLAYER_INFO   (S2C)  红/黑 + 旁观者名单 + 各自 name
 *  - CHESS_DRAW_REQUEST  (C2S)  发起求和申请
 *  - CHESS_DRAW_INVITE   (S2C)  通知对方有人求和
 *  - CHESS_DRAW_RESPONSE (C2S)  接受/拒绝求和
 *  - CHESS_DRAW_RESULT   (S2C)  双方告知求和结果
 *  - CHESS_RESIGN        (C2S)  认输
 *  - CHESS_SWITCH_REQUEST  (C2S)  发起切换身份(申请方填 targetId)
 *  - CHESS_SWITCH_INVITE   (S2C)  通知对方有人想换
 *  - CHESS_SWITCH_RESPONSE (C2S)  接受/拒绝
 *  - CHESS_SWITCH_RESULT   (S2C)  双方告知切换结果
 *  - CHESS_CHAT          (C2S + S2C)  局内聊天
 */
public final class ModNetwork {
    public static final ResourceLocation CHESS_INTERACT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_interact");
    public static final ResourceLocation CHESS_SYNC =
            new ResourceLocation(QishengChess.MOD_ID, "chess_sync");
    public static final ResourceLocation CHESS_OPEN_SCREEN =
            new ResourceLocation(QishengChess.MOD_ID, "chess_open_screen");
    public static final ResourceLocation CHESS_POPUP =
            new ResourceLocation(QishengChess.MOD_ID, "chess_popup");
    public static final ResourceLocation CHESS_PLAYER_INFO =
            new ResourceLocation(QishengChess.MOD_ID, "chess_player_info");
    public static final ResourceLocation CHESS_DRAW_REQUEST =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_request");
    public static final ResourceLocation CHESS_DRAW_INVITE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_invite");
    public static final ResourceLocation CHESS_DRAW_RESPONSE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_response");
    public static final ResourceLocation CHESS_DRAW_RESULT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_draw_result");
    public static final ResourceLocation CHESS_RESIGN =
            new ResourceLocation(QishengChess.MOD_ID, "chess_resign");
    public static final ResourceLocation CHESS_SWITCH_REQUEST =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_request");
    public static final ResourceLocation CHESS_SWITCH_INVITE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_invite");
    public static final ResourceLocation CHESS_SWITCH_RESPONSE =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_response");
    public static final ResourceLocation CHESS_SWITCH_RESULT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_switch_result");
    public static final ResourceLocation CHESS_CHAT =
            new ResourceLocation(QishengChess.MOD_ID, "chess_chat");

    private ModNetwork() {}

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_INTERACT, ChessInteractC2SPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_SYNC, ChessSyncS2CPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_OPEN_SCREEN, ChessOpenScreenS2CPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_POPUP, PopupS2CPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_PLAYER_INFO, SpectatorListS2CPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_DRAW_REQUEST, DrawPackets.Request::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_DRAW_INVITE, DrawPackets.Invite::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_DRAW_RESPONSE, DrawPackets.Response::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_DRAW_RESULT, DrawPackets.ResultPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_RESIGN, ChessResignC2SPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_SWITCH_REQUEST, SwitchPackets.Request::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_SWITCH_INVITE, SwitchPackets.Invite::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_SWITCH_RESPONSE, SwitchPackets.Response::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_SWITCH_RESULT, SwitchPackets.ResultPacket::receive);
        NetworkManager.registerReceiver(NetworkManager.c2s(), CHESS_CHAT, ChatPackets.Send::receive);
        NetworkManager.registerReceiver(NetworkManager.s2c(), CHESS_CHAT, ChatPackets.Broadcast::receive);
    }
}