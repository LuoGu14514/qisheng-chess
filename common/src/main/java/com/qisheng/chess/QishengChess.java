package com.qisheng.chess;

import com.qisheng.chess.pvp.SessionManager;

/**
 * 启升棋(Qisheng Chess)mod 顶层常量与全局生命周期钩子。
 *
 * <p>中国象棋 PVP mod,基于 Architectury 跨平台层。
 * <b>当前只编译 Fabric 平台</b>({@code gradle.properties} 的
 * {@code enabled_platforms = fabric});NeoForge 模块已移除。
 *
 * <h2>许可</h2>
 * 内嵌规则引擎 {@code com.qisheng.chess.engine.xqwlight}({@code Position.java} /
 * {@code Search.java})源自 XiangQi Wizard Light,原始头部为
 * <b>GNU GPL v2 或更高版本</b>(Copyright 2004-2008 www.elephantbase.net,
 * 2004-2013 www.xqbase.com)。发布到 Maven 的 xqbase Artifact 同样带 GPL 头,
 * 因此整个 mod <b>统一按 SPDX {@code GPL-2.0-or-later} 分发</b>;
 * 早期 {@code fabric.mod.json} 里的 MIT 声明已删除(与 GPLv2 不兼容)。
 * 详见仓库根目录的 {@code LICENSE}、{@code NOTICE}。
 * 纹理素材来自 TLM(tartaric_acid),为 CC BY-NC-SA 4.0,<b>仅限非商业</b>使用。
 */
public final class QishengChess {
    public static final String MOD_ID = "qisheng_chess";

    private QishengChess() {
        // utility
    }

    /**
     * Drop every cached session and reset the global mode.
     *
     * <p>{@link SessionManager} is a process-wide static singleton, so without this
     * hook the sessions of a previous world would survive into the next one when
     * the player leaves a single-player world and joins another (integrated server
     * restart, same JVM). Platform entry points call this from their
     * "server started" event.
     *
     * <p><b>It must not run on shutdown.</b> Sessions are what gets written into
     * each board's tile-entity NBT when the world is saved, and
     * {@code SERVER_STOPPING} fires <em>before</em> the final chunk save — clearing
     * here would wipe every stored game at exactly the moment it is flushed to
     * disk. Clearing on the way <em>in</em> gives the same isolation with none of
     * the data loss.
     */
    public static void onServerStarted() {
        SessionManager.get().resetAll();
    }
}
