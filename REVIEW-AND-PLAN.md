# 启升棋 qisheng-chess — 代码审查与下一步规划

审查对象：`D:\代码\qisheng-chess`，工作树干净，HEAD = `d22e92e`
审查方式：全量阅读 `common/src/main/java` 下 42 个 Java 文件 + fabric/neoforge 入口共 47 个文件、全部资源 JSON、构建脚本；并**实际跑通了一次干净构建**（见第 2 节）。
审查日期：2026-10-02

> 说明：`PLAN.md`（2026-09-26 快照）已经严重过期——它声称"网络包/GUI/指令/lang 均未实现""暂停中"，而实际代码早已完整实现这些部分。本文档以代码为准，替代 `PLAN.md` 的结论部分。

---

## 0. 结论摘要

模组**功能骨架已经完整**：方块 → 右键开 GUI → 服务端权威走子 → 同步/旁观/求和/换位/评论，一条链路都通了，并且能构建出可装载的 Fabric jar。当前的问题不是"缺功能"，而是**四类债务**：

| 类别 | 数量 | 代表问题 |
|---|---|---|
| 阻断级 | 4 | 引擎是 **GPLv2** 却被声明为 MIT；构建依赖 `-SNAPSHOT` 漂移导致 JDK 17 下必然失败；NeoForge 侧半删除（源码语法都不完整）；聊天输入超 64 字直接抛异常 |
| 逻辑缺陷 | 9 | 会话只用 `BlockPos` 作键、**忽略维度**；掉线处理把状态置成 `WAITING`；`joinGame` 不查占用；`swapRoles` 只能换一次 |
| 性能/体验 | 6 | 渲染线程**每帧**解析 FEN + 最多 90 次整盘走法生成；每次走子往聊天灌一条 FEN 调试行 + 一条 11 行 ASCII 棋盘 |
| 工程债 | 20+ | `Search.java`(15.6KB) 零调用；3 个同名 `ModClient`；GUI 文案 100% 硬编码中文且只有 `zh_cn`；无测试源集、无 README/LICENSE |

**建议的第一优先级不是加功能，而是**：① 定下引擎许可策略；② 把构建钉死成可复现（当前在这个环境下 `gradlew build` 是**失败的**）；③ 修掉会崩溃/刷屏的那几个点。这三件事做完才谈得上发版。

---

## 1. 项目现状（它现在到底是怎么运作的）

- **平台**：Architectury 跨平台工程，`enabled_platforms = fabric,neoforge`，但 `settings.gradle` 里 **NeoForge 子项目已被注释掉**（`// include 'neoforge' (Fabric-only build)`）——实际是 Fabric-only。
- **交互链路**：放置 `qisheng_chess:cchess` 方块 → 5 格内右键 → 服务端 `GameBroadcaster.sendOpenScreen` → 客户端 `ChessOpenScreenS2CPacket` 在 `mc.execute` 里 `setScreen(new CChessBoardScreen(...))`。所有操作走 `CHESS_INTERACT` C2S（`ACTION_JOIN=0 / SELECT=1 / MOVE=2`），服务端校验后广播 `CHESS_SYNC` + `CHESS_PLAYER_INFO`。
- **服务端权威性**：`GameLogic.trySelect/tryMove` 是唯一裁判，客户端只做"看起来能不能走"的预判。`SessionManager`（静态单例）持有 `ConcurrentHashMap<BlockPos, GameSession>`，一张 `Map<UUID,BlockPos>` 索引人→局。
- **规则引擎**：内嵌 **xqwlight**（Morning Yellow 的 "XiangQi Wizard Light"，2004–2013），`Position` 用 16×16 `byte[256]` 棋盘、`mv = src | (dst<<8)` 走法编码、32 位 RC4 派生 zobrist（原 C 版是 64 位）。
- **对局玩法**：红先、吃子重置自然限着计数、`repStatus(3) > 0` 判重复和棋、`moveNum > 60` 判限着和棋、将死判负。求和 / 认输 / 红黑互换 / 旁观 / 局内评论都实现了。
- **生命周期**：会话**完全不落盘**（`CChessTileEntity` 是空壳，注释写着"Phase 2 才存 NBT"）；`ModRegistry.init()` 也没有服务器停止钩子，所以静态单例跨存档残留。

---

## 2. 构建与环境（实测结论，重要）

### 2.1 当前构建是**失败的**，需要修

我实际跑了四种组合，结果：

| 组合 | 结果 |
|---|---|
| `JAVA_HOME=JDK 17` + `gradlew :common:compileJava` | ❌ `Could not resolve net.fabricmc.unpick:unpick:3.0.0-beta.9 … Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM.` |
| `JAVA_HOME=JDK 25` + 同上 | ❌ `Cannot find a Java installation … matching: {languageVersion=17}`（找不到 17 工具链） |
| `JAVA_HOME=JDK 25` + `-Porg.gradle.java.installations.paths=…jdk-17…` + `:common:compileJava :fabric:compileJava` | ✅ **BUILD SUCCESSFUL** |
| 同上 + `gradlew clean :fabric:remapJar` | ✅ **BUILD SUCCESSFUL**（25 秒，12 个任务全部执行，产出 jar 与 2026-09-28 那次**逐字节同尺寸** 162794 B） |
| 同上 + `gradlew build`（用户最常用的命令） | ❌ 在 `:common:test` 上炸：`Could not create task ':common:test' … DefaultReportContainer … Type T not present`（注意 `:fabric:compileTestJava` 是 NO-SOURCE 正常通过，只有 `:common:test` 建不出来） |

> 编译期还有一个告警值得顺手清掉：`CChessBoardBlock.java:60-63` 覆写了已废弃的 `BaseEntityBlock#getRenderShape()` 并返回 `RenderShape.MODEL`——`BaseEntityBlock` 的默认实现已经是 `MODEL`，这个覆写是多余的，删掉即可（编译输出：`CChessBoardBlock.java使用或覆盖了已过时的 API`）。

**根因有三条，都是可修的：**

1. **Loom 用了 `-SNAPSHOT`**（`build.gradle:3`：`id 'dev.architectury.loom' version '1.11-SNAPSHOT'`）。快照会漂移：现在解析到 `1.11.458`，它传递依赖的 `unpick 3.0.0-beta.9` **要求 JVM ≥ 21**。→ 必须钉成固定版本号。
2. **Gradle 8.13 跑在 JDK 25 上不兼容**（`Type T not present` 是 Gradle 8.13 在 JDK 24/25 上的已知类扫描问题）。→ 要么把 Gradle 换成 9.x，要么用 JDK 21 跑 Gradle。
3. **Java 17 工具链不在自动探测路径里**（装在 `D:\工具\jdk-17.0.20.1+1`）。→ 需要在 `gradle.properties` 里显式声明。

### 2.2 建议的修法（`gradle.properties` 追加）

```properties
# Gradle 用 JDK 21 运行（JDK 25 会触发 Gradle 8.13 的 Type T not present）
org.gradle.java.home=C:/Program Files/.../jdk-21
# 让 Gradle 找到编译用的 JDK 17
org.gradle.java.installations.paths=D:/工具/jdk-17.0.20.1+1
org.gradle.java.installations.auto-download=false
```

然后 `build.gradle` 的 loom 版本从 `1.11-SNAPSHOT` → 固定发布版（如 `1.11.458`）。

### 2.3 构建产物现状

- `fabric/build/libs/qisheng_chess-fabric-0.1.1.jar`（162 KB，2026-09-28）——上一次完整构建成功，可用。
- `common/build/libs/…-transformProductionNeoForge.jar` 只有 **166 字节**（空壳），是 NeoForge 移除后的残留。
- 只有 `gradlew.bat`，**没有 `gradlew`**（Unix 脚本缺失）。
- **没有测试源集**（`**/src/test/**` 零文件）。
- **没有 README / LICENSE / NOTICE**。

---

## 3. 代码审查发现

### 3.1 P0 — 必须先决策/修复

**P0-1 引擎许可冲突（法律问题，最优先）**
`engine/xqwlight/Position.java:1-21` 与 `Search.java:1-21` 的文件头是**逐字的 GPLv2 声明**：
> "Position.java - Source Code for XiangQi Wizard Light … Copyright (C) 2004-2008 www.elephantbase.net … under the terms of the GNU General Public License … either version 2 of the License, or (at your option) any later version."

而 `fabric.mod.json:9` 写 `"license": "MIT"`，`QishengChess.java:9` 的 javadoc 写"MIT/CC BY-NC-SA 4.0"。**GPLv2 与 MIT 不兼容**，也不允许被再许可成 MIT/CC BY-NC-SA。三个引擎文件里也没有 elky / tartaric_acid / TLM 的任何署名。
→ 三条出路，需你拍板：(a) 整体改为 **GPLv2**（最省事、最合法）；(b) **重写/替换引擎**（工作量最大，但可以保持 MIT）；(c) 联系原作者取得例外授权。**在此之前不建议发布任何版本。**

**P0-2 构建不可复现** — 见第 2 节。

**P0-3 NeoForge 侧是半删除状态**
- `settings.gradle` 排除了 neoforge，但 `gradle.properties` 仍写 `enabled_platforms = fabric,neoforge`，而 `common/build.gradle` 用这个变量去 `architectury { common ... }`。
- `neoforge/QishengChessNeoForge.java` **源码语法都不完整**：构造函数 `{` 开在 16 行，22 行日志之后文件直接结束，**构造函数与类的收尾 `}` 都缺失**。它现在不被编译所以没暴露。
- `neoforge/NeoForgeEvents.java` 也没有注册 `/qisheng` 命令。
→ 二选一：**彻底删掉 neoforge 目录 + 清掉 `enabled_platforms`**（推荐，符合现状），或者补齐它。

**P0-4 聊天输入超长直接抛异常（会崩客户端）**
`ChatBoxWidget.charTyped`(ChatBoxWidget.java:71) 不限制长度；回车走 `CChessBoardScreen.java:431-434` → `ChatPackets.Send.write` → `buf.writeUtf(text, MAX_LEN=64)`(ChatPackets.java:24,33)。**输入超过 64 个字符按回车 → `EncoderException("String too big …")`**，异常在 `keyPressed` 里抛出。
→ 3 行修复：`charTyped` 里按 `ChatPackets.MAX_LEN` 截断，发送前再兜一次。

**P0-5 认输确认框文案错乱**
`CChessBoardScreen.java:353-356` 调用 `ActionPopup.show("确定认输?", ERROR, "认输", "取消", …)`，但 `ActionPopup.render` **硬编码渲染 "接受" / "拒绝"**(ActionPopup.java:67-68)，传进去的 `acceptLabel/rejectLabel` 存在字段里却从未使用。玩家看到的确认框是"确定认输?"下面两个按钮写着"接受/拒绝"。
→ 用上标签字段即可。

### 3.2 P1 — 逻辑缺陷

**P1-1 会话忽略维度**（`SessionManager.java:55-159`）
`getOrCreate(ServerLevel level, BlockPos pos)` 里 `level` **参数被完全忽略**，`sessions` 是 `ConcurrentHashMap<BlockPos, GameSession>`。**主世界和下界里同一个 `BlockPos` 会共用同一局棋**。`remove(pos)` 同样不分维度，拆掉一块棋盘会把另一维度的局一起删掉。
→ 键改成 `record BoardKey(ResourceKey<Level> dim, BlockPos pos)`。

**P1-2 掉线处理自相矛盾**（`PlayerDisconnectHandler.java`）
类 javadoc 写 "Mark the game FINISHED"，代码却 `setState(GameState.WAITING)`；同时把 `redPlayer/blackPlayer` 置 null、棋盘重置为初始局面。后果：
- 幸存者的 `playerInGame` 条目**没有被清理**（只 evict 了掉线者），而会话里已经没有红黑角色 → 幸存者 `containsPlayer(self) == false`，`takeOver` 又被 `playerInGame.containsKey` 挡住，**只能靠重新右键 joinGame 才能恢复**；
- 不发 `broadcastGameOver` → 幸存者的 GUI 收不到终局，界面停在旧局面；
- 用 `player.serverLevel().getPlayerByUUID(survivorId)` 找幸存者，**跨维度直接返回 null**，popup 静默丢失。

**P1-3 `joinGame` 不做占用校验**（`SessionManager.java` joinGame）
只看 `state == WAITING` 和红/黑是否为空，**既不查 `playerInGame.containsKey(uuid)` 也不清理 `playerSpectating`**。已经在别的棋盘上打着的玩家可以再占一个位，留下陈旧索引；正在旁观同一局的人可以直接加入而不被移出旁观表。

**P1-4 `swapRoles` 只能单向换一次**
`SessionManager.swapRoles(UUID a, UUID b)` 只匹配 `a == red && b == black`。第一次（红发起）能成功，**之后再想换回来就不匹配了**。而且它忽略 `pos`，靠"一个人只在一局里"的约束兜底——而 P1-3 恰好破坏了这个约束。

**P1-5 窗口缩放会清空 GUI 状态**
`CChessBoardScreen.init()`(:225-253) 每次都 `new` 全新的 `specList / redBadge / blackBadge / chatBox`，`badge` 用 `null,null` 构造，**从不把已缓存的 `this.roster` 重新灌回去**（`applyRoster` 只在收包时调用一次）。玩家一缩放窗口：**红黑方名字变成 "?"、旁观列表变回"棋局加载中…"、聊天历史和正在输入的草稿全丢**，要等下一次 `CHESS_PLAYER_INFO` 才恢复。
→ 6 行修复：`init()` 末尾重新 `applyRoster(this.roster)`，聊天记录提到 Screen 字段或复用实例。

**P1-6 弹窗栈是静态的且永不清理**
`PopupOverlay.ACTIVE` / `ActionPopup.ACTIVE` 都是 `static`，`CChessBoardScreen` **没有覆写 `removed()`/`onClose()`**，而 `PopupOverlay.clear()`(PopupOverlay.java:70) 和 `ActionPopup.clear()`(ActionPopup.java:37) **全仓零调用点**。后果：
- `autoSec=0` 的粘性 WARN/ERROR chip 会**跟着玩家出现在另一块棋盘的 GUI 上**；
- `ActionPopup` 里的 `Runnable` 捕获了旧 Screen → 泄漏；
- `ActionPopup.tick()` 是**空方法**(ActionPopup.java:33-35)，所以求和/换位邀请被对方 CANCEL 之后（`CChessBoardScreen.java:182` 只弹了一行文字），**"接受/拒绝"按钮依然活着，点下去会拿旧的 pending 状态去发响应**。

**P1-7 `/qisheng` 命令没有权限门槛**
`ModCommands.java` 所有子命令都是 `Source.getPlayerOrException()`，**没有任何 `.requires(src -> src.hasPermission(2))`**。普通玩家可以执行 `/qisheng mode pvc`（改**全局单例** `SessionManager.globalMode`）和 `/qisheng reset`（清空当前局并把双方角色置 null）——这是可以直接被用来破坏别人对局的。

**P1-8 命令坐标与盘面标注上下颠倒**
`ModCommands.visualToInternal(int)` 用 `rank = visualSq / 9; COORD_XY(file + FILE_LEFT, rank + RANK_TOP)`，`rank + 3` 落在内部分第 3 行——也就是 `CChessUtil.boardToAscii()` 打印出来的**顶行（标着 visual 9 = 黑方底线）**。所以 `/qisheng select 0` 选中的是**显示为第 9 行的位置**。file 轴（a..i）是对的。
→ 要么改 `visualToInternal` 的 `rank = 9 - visualSq/9`，要么把盘面标注改成和命令一致。

**P1-9 每次同步往聊天灌调试信息（刷屏）**
`ChessSyncS2CPacket` 在包尾对每个收件人 `sendSystemMessage("§7[启升棋同步] " + sdStr + " | " + stateStr + " | FEN=" + fen)`；叠加 `BoardMessages.sendTo` 每次推**整盘 11 行 ASCII**（调用点：`ChessSyncS2CPacket`、`CChessBoardBlock.java:111/114/133/140/147`、`ChessInteractC2SPacket.java:99/102/112`、`ModCommands.java:275`）。
→ **一次普通走子会给双方各推 1 条 FEN 调试行 + 1 条 11 行棋盘**。而且 popup 系统（`PopupOverlay`，存在意义就是"取代旧的刷聊天"）明明已经做好了这个功能。→ 直接删掉这两处 chat 输出。

### 3.3 P2 — 性能与体验

**P2-1 渲染线程每帧重算一切**（`CChessBoardScreen.render()`:399-425）
- 每帧 `Position.fromFenString(fen)` —— 一次完整 FEN 解析 + 新 `Position` 分配（约 3.3 KB），60 FPS 下就是 ~200 KB/s 垃圾；
- 选中棋子后 `drawLegalDots` 对 90 个格子逐个 `pos.legalMove(Position.MOVE(sel, dst))`，而 `legalMove` 内部会**生成整盘走法 + 试走 + 撤销**。单帧量级上千次走法生成。
→ 改成"FEN 变了才重算"，把合法落点缓存成 `boolean[90]`（`applySync` 时算一次）。

**P2-2 合法落点各算各的**
客户端本地算落点、服务端再判一次。目前两边用同一个 `Position`，结果一致，但这意味着**将来换引擎/AI 就会出现"界面显示能走、点了说非法"**。
→ 中期应把落点集合放进 `CHESS_SYNC` 由服务端下发。

**P2-3 收包不校验坐标 / 不限长**
- `ChessSyncS2CPacket` 读了 `pos` 却**从不与当前 Screen 的 boardPos 比对**（`SpectatorListS2CPacket` 是做比对的）；`DrawPackets.Invite/Result`、`SwitchPackets.Invite/Result`、`ChatPackets.Broadcast` 也都只判 `instanceof CChessBoardScreen` 就交付 → **站在 A 棋盘上会收到 B 棋盘的数据**。
- `SpectatorListS2CPacket.read` 里 `int n = buf.readInt(); new PlayerEntry[n]` **没有上限校验** → 恶意/异常包可以让客户端 OOM。
- `PopupS2CPacket.receive` 的 `Severity.values()[buf.readByte()]`、`DrawPackets/SwitchPackets` 的 `Result.values()[ord]` 都**没有越界保护** → 一个坏包 = 客户端崩溃（AIOOBE）。
- `ChessSyncS2CPacket` 的 `readUtf()` 未限长。
→ 统一加一个 `PacketGuards` 工具：`readEnum(buf, values)`、`readBoundedList(buf, max)`、`checkPos(screen, pos)`。

**P2-4 引擎入口没有防护**
`legalMove`(Position.java:873-882) 不做边界检查；`GameLogic.java:110` 的 `src/dst < 0 || >= 256` 是**唯一的挡箭牌**。畸形 FEN 永不抛异常也永不校验（少将/多将/兵在底线全部静默接受）；`undoMakeMove()` 越过最后一次 `setIrrev()` 后会 `PIECE_VALUE[-8]` 越界。
→ 抽一个 `ChineseChessEngine` 门面，把所有越界坑挡在受检入口后面。

**P2-5 GUI 细节问题（widget 审计）**
- `ActionPopup.mouseClicked` **只对 `peekFirst()` 做命中测试**(ActionPopup.java:85-98)，但 `render` 会把栈里最多 3 个都画出来(45-70) → 第 2、3 个弹窗的按钮**看得见点不动**；
- `PopupOverlay.mouseClicked` **无论是否删掉 chip 都 `return false`**(:113) → `CChessBoardScreen.java:659` 的 `if (…) return true;` 是死分支，**点掉一个提示 chip 会顺带在棋盘上走一步**；而且 chip 矩形在首帧渲染前是 `0,0,0,0`，点 GUI 左上角会误删 chip；
- 两个栈的命中区**重叠**（PopupOverlay 到 y≈154，ActionPopup 按钮在 y≈63-76，都居中）→ 点普通提示可能点中邀请按钮；
- `ChatBoxWidget` 渲染用**固定** `HISTORY_H=100 / INPUT_H=20`(:118-151)，完全无视自己的 `getHeight()`；而 Screen 在 320×240 时只给它 80 高 → **输入行画在屏幕外但仍然可点击**；
- 旁观列表只裁剪"行首"(SpectatorListWidget.java:101)，某行起点落在底边上方 1 像素就能**画出框外 17 像素**盖在边框上；
- `ChatBoxWidget.pushMessage` **无条件把 `scroll=0`**(:54)，和它自己上一行的注释"unless the user scrolled up"矛盾 → **来了新消息就把正在翻看历史的人拽回底部**；
- 旁观列表的滚轮方向和聊天框**相反**，其中一个肯定是错的；
- 棋盘点击的 `mouseClicked` 在 :664-674 就 `return true` 了，**永远走不到 `super.mouseClicked`(:676)**，于是 `ChatBoxWidget.mouseClicked`(:96) 里唯一一处"清除输入焦点"的代码永不执行 → **点棋盘不会让聊天框失焦，按键继续被吞**；`ChatBoxWidget.mouseClicked` 还忽略 `button`，右键也会聚焦输入框（而模组的接手提示正是让玩家右键）；
- 聊天文本只过滤 `\p{Cntrl}`(ChatPackets.java:42)，**`§` 没被过滤** → 玩家名字/聊天里的 `§c`、`§k` 会被当成颜色码渲染，可伪造消息；
- `PlayerAvatarCache` 是**永不过期**的渲染线程缓存，`invalidate/clear`(:51-52) 零调用点；`catch (Throwable)`(:44-46) 会把一次失败的 fallback 头像**永久缓存**。

### 3.4 P3 — 工程债与死代码

**死代码（已验证零调用点）**
- `engine/xqwlight/Search.java`（**15.6 KB 整个文件**）+ `Util.shellSort/binarySearch` —— `new Search(...)` 全仓零调用；
- `client/ModClient.java` ×3（common/fabric/neoforge），**后两个与 common 的 FQN 完全相同**（`com.qisheng.chess.client.ModClient`）→ 类路径重复。fabric 版注释说"Loom's source-set merge picks this up at build time"——**这个说法是错的**，Loom 不做跨 project 源集合并；
- `CChessUtil` 的 `getClickSquare`(:44)、`squareCenter`(:59)、`piecesIndex`(:135)、`isBlack`(:143)、`isPlayer`(:147)、`isMaid`(:151)（随 `77174bd Remove live-on-block board surface renderer` 失效），连带 `import Vec3 / Direction` 也变成未使用；
- `CChessBoardBlock.spawnHighlight`（空实现桩）、`CChessBoardBlock.getRenderShape`(:60-63，见 2.1 注)、`GameMessages.lookAtTopFace`(:47)；
- `CChessBoardScreen.selfId`（自己标了 `@SuppressWarnings("unused")`）、`CHAT_ROWS`(:66)、`ActionPopup.clear/tick/isActive`、`ActionButtonsWidget.isEmpty`、`PlayerBadgeWidget.getPlayerId/getRole`、`SpectatorListWidget.spectatorAt/getRoster`、`Chip.dismissed`、`Action.enabled`（永远是 `true`）；
- `assets/.../lang/zh_cn.json` 里 14 个 `item.qisheng_chess.piece_*` key —— **对应的物品从来没注册过**（`ModItems` 只注册了 `cchess`）。

**其他债**
- **i18n 完全缺失**：`client/` 包 grep `Component.translatable` = **0 命中**，`Component.literal(` = 15 处 → GUI/聊天文案 100% 硬编码中文；`ModCreativeTabs.java:33` 用了 `translatable("itemGroup.qisheng_chess")` 但**没有 `en_us.json`** → 英文客户端直接显示原始 key。
- **无测试源集**：`**/src/test/**` 零文件。`GameLogic`/`SessionManager`/`CChessUtil` 都是纯逻辑，非常好测。
- **`build.gradle` 不是合法 UTF-8**（read 工具直接报 `invalid UTF-8 text`），中文注释目前是乱码（`// Architectury 瀹樻柟浠撳簱`）。
- **重复代码**：1 像素四边框绘制在 5 个 widget 里各抄了一遍（只有 `CChessBoardScreen.java:639-643` 抽成了方法）；头像两遍 blit 在 `SpectatorListWidget.java:155-156` 和 `PlayerBadgeWidget.java:68-69` 完全一样；同一个按钮栈有**三个不同的高度常量**（`CChessBoardScreen.java:64` `BTN_H=24` vs `ActionButtonsWidget.java:40` `BTN_H=20`；`maxButtons=7` 而实际最多 5 个）。
- **注释与代码不符**：`CChessBoardBlock`/`PlayerDisconnectHandler` 的 javadoc、`FabricClient` 声称调用不存在的 `ModClient.registerBlockEntityRenderers()`、`ModItems` 说"Add to a tab later"（已经加了）。
- **方块不可旋转**：`blockstates/cchess.json` 只有一个无属性 variant，没有 `facing` → 棋盘在世界里的朝向是死的。
- **`SessionManager.globalMode`** 是全局单例、不落盘、跨存档残留。
- **`createShape`/`getShape` 未覆写**，而 `Properties` 用了 `.noOcclusion()` —— 棋盘方块的碰撞箱是整块立方体（这个属于设计取舍，列出备查）。

**已确认不是 bug 的两处**（免得白改）：
- `Position.makeMove`(Position.java:550-563) 失败时会自己 `undoMovePiece()` 再 `return false`，不改 `moveNum/distance/sdPlayer` → **`GameLogic.java:118-119` 的 `KING_EXPOSED` 分支不会留下脏棋盘**；
- `PopupOverlay.tick()` 用 `System.currentTimeMillis()` 而不是帧计数 → **帧率高低不影响提示消失速度**；
- 8 个 widget 文件里**没有任何 `RenderSystem`/scissor/pose/blend 调用** → 没有 GL 状态泄漏；所有包处理都经过 `mc.execute` → 没有渲染/网络线程竞态。

---

## 4. 下一步更新规划（版本路线）

### v0.1.2 「止血版」—— 目标：可复现构建 + 不崩 + 不刷屏
1. **决定引擎许可**（P0-1）并在 README/NOTICE 里落实署名。
2. **钉死构建**（P0-2）：Loom `1.11-SNAPSHOT` → 固定版本；`gradle.properties` 加 `org.gradle.java.installations.paths`；决定 Gradle 运行用 JDK 21 还是升级 Gradle 9.x；补 `gradlew`。
3. **清理 NeoForge 残骸**（P0-3）：删 `neoforge/`，`enabled_platforms = fabric`。
4. **修崩溃**：聊天 64 字截断（P0-4）；补上 `PopupS2CPacket`/`DrawPackets`/`SwitchPackets` 的 enum 越界保护与 `SpectatorListS2CPacket` 的长度上限（P2-3）。
5. **修文案**：`ActionPopup` 用上 `acceptLabel/rejectLabel`（P0-5）。
6. **停掉刷屏**（P1-9）：删 `ChessSyncS2CPacket` 的 FEN 调试行；`BoardMessages.sendTo` 的整盘 ASCII 改成只在 `/qisheng board` 输出。

### v0.1.3 「正确性版」—— 目标：多人多局不串
7. 会话键加维度（P1-1）。
8. 重写掉线流程（P1-2）：置 `FINISHED`、发 `broadcastGameOver`、清理幸存者索引；或者干脆"掉线即本局作废并允许自由重开"。
9. `joinGame` 补占用校验、`swapRoles` 改成双向且带 pos（P1-3/P1-4）。
10. `/qisheng` 加权限门槛（P1-7）；修命令坐标颠倒（P1-8）。
11. GUI 状态在 `init()` 里重建（P1-5）；弹窗栈加 `removed()` 清理 + 邀请取消即失效（P1-6）。

### v0.2 「体验版」
12. **i18n**：`client/` 文案全部走 `Component.translatable`；补 `en_us.json`；删掉 14 个孤儿 `piece_*` key。
13. **渲染优化**（P2-1）：FEN 缓存 + 合法落点缓存。
14. 落点集合由服务端下发（P2-2）。
15. 棋盘加 `facing` 属性可旋转；`BlockState` 朝向决定 `viewerIsBlack` 之外的盘面朝向。
16. GUI 细节打包修（P2-5 整段）。
17. 音效（走子/吃子/将军）、走子动画、最近一步高亮。

### v0.3 「引擎版」
18. **对局持久化**：`CChessTileEntity` 存 FEN + 双方 UUID + 状态（现在重启就清空）。
19. **PVC 人机**：`BoardMode.PVC` 目前只有个枚举值，`Search` 是死代码。要用它必须先修引擎的**时间预算缺陷**（`searchFull/searchQuiesc` 内部从不读时间 → `searchMain(1000)` 可能跑几分钟，在服务端 tick 线程上就是卡服）和 `getKNPS()` 除零。
20. 抽 `ChineseChessEngine` 门面 + `Position copy()`。

### v0.2/v0.3 完成状态（2026-10-02 末轮审计）

**已落地**:
- 12 i18n 客户端 — 全部走 `translatable`,`en_us.json` 71 键补齐,孤儿 `piece_*` 经审计仅 11 个全部被引用 → 不存在孤儿。
- 13 渲染缓存 — `cachedFen/cachedPos/destCache` 字段 + `refreshBoardCaches()`。
- 14 服务端下发合法落点 — `LegalDestsBitmap` (32 字节位图) 随 `broadcastSync` + `sendOpenScreen` 下发,客户端命中表优先。
- 15 棋盘可旋转 — `CChessBoardBlock.FACING` (HORIZONTAL_FACING, 4 朝向) + `getStateForPlacement/rotate/mirror` 覆盖,`sendOpenScreen` 追加 `flipped` 字节,`boardFlipped = viewerIsBlack XOR flipped`。
- 16 GUI 细节 — P2-5 全部 15 项:ActionPopup 实例态 + dismiss(Tag)、PopupOverlay 实例态 + 命中消费、ChatBoxWidget 长度上限 + scissor + scroll 保留 + § 过滤、SpectatorListWidget scissor + 滚轮方向、CChessBoardScreen 棋盘点击清输入焦点、PlayerAvatarCache LRU + TTL、TextSanitizer § 剥除、ActionButtonsWidget BTN_H=24 与 Screen 对齐、`Action.enabled` 删除。
- 18 对局持久化 — `CChessTileEntity` 存 8 键 NBT(FEN/红UUID/黑UUID/sdPlayer/moveCount/result/mode/state),`load/saveTag` + `fromTag` 防御 + 双空降 WAITING。
- 19 PVC — `PvcController` 守护线程 + `GameLogic.tryEngineMove` + `SessionManager.joinGame` PVC 分支 + `isComputerToMove` 三状态守卫 + 服务端 i18n 三键。
- 19 引擎时间预算加固 — `Search.searchFull/searchQuiesc` 每 1024 节点读 `outOfTime()` + `ABORTED = -MATE_VALUE` + `fallbackMove()` 兜底 + `getKNPS()` 除零修复 + `deadline/stopped` 复位。
- 20 `ChineseChessEngine` 门面 + `searchBestMove/firstLegalMove` 私有棋盘。
- **服务端 i18n 完整化(本次新增)** — ChessInteractC2SPacket / GameMessages / ModCommands 全部硬编码中文 → translatable,lang 文件 116→119 键,zh=zh=en 完全对称;唯一残留为 `[qisheng]` 前缀的 admin/operator 反馈。

**未落地(本轮可选)**:
- 17 音效/走子动画/最近一步高亮 — **v0.2.1 已落地**:最近一步高亮 (`drawLastMoveOverlay` 画 `0xC0FFEB6B` 半透明黄底色块,服务端 `GameSession.lastMoveSrc/lastMoveDst` 随 `CHESS_SYNC` + `CHESS_OPEN_SCREEN` 各加 4 字节下发,`ChineseChessEngine.isSquare()` 兜底越界) + 走子音效 (`SoundEvents.NOTE_BLOCK_PLING`,`applySync` 检测 `lastSrc/lastDst` 变化时播放,`Minecraft.execute()` 派发到渲染线程)。走子动画 (`Position.movePiece` 时 200ms 滑动插值) 仍未做。
- `[qisheng]` admin 反馈 — **v0.2.1 已落地**:`ModCommands.doPurge/doMove/doSelect` 三处 `Component.literal("[qisheng] ...")` → `translatable`,lang 文件 119→122 键对称。
- 实机验证 — 容器无 LWJGL Display 与 EULA TTY,无法跑 MC client/server。
- **v0.3.1 (2026-10-02 末轮 + 引擎抽象 + 国际象棋)**:BoardVariant 注册表 + XiangqiVariant 迁移 + InternationalChessVariant 完整 FIDE 规则 + variant-aware 重构(GameSession / GameLogic / GameBroadcaster / PvcController)+ 网络协议末尾 1 字节 variantId + CChessBoardScreen 国际象棋占位提示;测试 60→**79**(XiangqiVariantTest 9 + InternationalChessVariantTest 11 + LegalDestsBitmapTest 6)全绿。详见 §9。
- **v0.4.0 (2026-10-03 + 五子棋 + 围棋 + 独立方块)**:在 v0.3.1 引擎抽象之上新增 5 个 BoardVariant(xiangqi/international/gomoku/go9/go19);抽 `AbstractChessBoardBlock` 基类,新建 `GomokuBoardBlock` + `GoBoardBlock`(围棋方块支持 `[size=9|19]` BlockState);`GameSession.boardState` 字段重构成 variant-specific,`getChessData()` 走 `instanceof Position` fallback;`CChessTileEntity.ensureSession(String)` overload 让新会话继承方块 variantId;`CChessBoardScreen` 全 variant-aware: `cols()/rows()` 动态 + 简化五子棋 / 围棋渲染 + 共享 `drawStone`;测试 79→**119**(GomokuVariantTest 19 + GoVariantTest 21 + 旧 79)全绿;lang 122→133 键对称(5 变种显示名 + 3 方块/物品名)。详见 §10。

---

## 5. 代码优化计划（工程改造）

按"投入产出比"排序，每项都标注了风险：

| # | 改造 | 收益 | 成本/风险 |
|---|---|---|---|
| 1 | **引入测试**：`common/src/test/java` + JUnit5。优先覆盖 `GameLogic.trySelect/tryMove`（含越界、轮次、将帅暴露）、`SessionManager` 的 join/takeOver/evict、`CChessUtil.boardToAscii`、`Position` 的 FEN 往返与走法生成 | 目前**零测试**，而这些都是纯逻辑，收益最大 | 低。需要 `build.gradle` 加 `testImplementation`，并解决 `:common:test` 建不出来的问题（升级 Gradle 或改跑法） |
| 2 | **抽 `ChineseChessEngine` 门面**：`parseFen`(带校验)、`List<int[]> legalMoves(int sq)`、`Result applyMove`，把 `Position` 的越界坑（`legalMove` 无边界检查、`undoMakeMove` 越界、畸形 FEN 静默接受）全挡在受检入口后 | 所有调用点都变安全、可测 | 中。纯包装，不动 `Position` 内部 |
| 3 | **网络层收口**：一个 `PacketGuards`（限长 / enum 越界 / 坐标比对），并统一"共享 buf 广播"的写法 | 消除 5 处客户端可崩溃点 + 跨棋盘数据串台 | 低 |
| 4 | **GUI 状态上提**：把 `roster`/聊天记录/输入草稿从 widget 实例提到 Screen 字段，widget 变纯渲染器 | 一次修掉缩放丢状态（P1-5）、弹窗不清理（P1-6）等一串问题 | 中。`CChessBoardScreen` 727 行，建议**先只做 `init()` 重建**这一步 |
| 5 | **删死代码**：2 个空壳 `ModClient`（同 FQN `com.qisheng.chess.client`，零调用点，只留一句指向已删方法的陈旧 javadoc）、`GameMessages.lookAtTopFace`、`CChessUtil` 若干未用方法、无用 getter/字段 | 消除 FQN 冲突，减重 | 低。**修正**：原表把 `Search.java` 与 `Util` 也列为待删，这是错的 —— 见下方"关于引擎类" |
| 6 | **抽公共绘制工具**：`drawBorder`、`drawPlayerHead`，统一按钮高度常量 | 消除 5 处复制粘贴与 3 个冲突常量 | 低 |
| 7 | **本地化**：`client/` 全部改 `translatable` + 补 `en_us.json` | 英文用户可用 | 中。文案量大，可与 v0.2 合并 |
| 8 | **持久化**：`CChessTileEntity` 落 NBT + 服务器生命周期钩子清理 `SessionManager` | 存档切换不再残留旧局 | 中 |
| 9 | **构建规范化**：固定 Loom 版本、补 `gradlew`、`build.gradle` 转成 UTF-8、补 README/LICENSE/NOTICE、`.gitignore` 决定是否放行新文档 | 新人可上手 | 低 |
| 10 | **引擎现代化**（仅当要做 PVC）：给 `searchFull/searchQuiesc` 传 deadline，用已有的 `allNodes` 每 N 个节点中断；修 `getKNPS()` 除零；把 `public static` 的 zobrist/book/Random 收进实例 | 让 Search 真正可用 | 中。**不要把 byte 棋盘改成对象模型**——`generateMoves`(683-871) 与 `checked`(932-986) 都假设字节布局 |

**明确不建议做的**：重写棋盘表示（`byte[256]` + `mv = src|(dst<<8)`）——收益小、风险大，`generateMoves` / `checked` / zobrist 全都耦合在这个布局上。

**关于引擎类（对原表第 5 项的修正，已 grep 逐个验证，不要凭猜测删）**：

| 类 | 结论 |
|---|---|
| `engine/xqwlight/Search.java` | 只被 `QishengChess.java:14` 的 javadoc 提到，**没有任何代码调用**；但它是 v0.3 做 PVC 的必需品，而且 MC-free、可单测 ⇒ **保留** |
| `engine/xqwlight/Util.java` | **不能删**：`Position.java:1087` 与 `Position.java:1091` 都在调 `Util.binarySearch(lock, bookLock, 0, bookSize)`（开局库）。只有 `Util.shellSort` 是纯给 `Search` 用的 |
| `network/ChessOpenScreenS2CPacket.java` | **不是死代码**：`GameBroadcaster.java:101` 真的在发 `ModNetwork.CHESS_OPEN_SCREEN`（内部走 `mc.execute(...)`，因为 S2C 回调跑在 Netty 线程上） |

---

## 6. 建议的执行顺序

```
第一步（今天就能做完）：v0.1.2 的 6 项 —— 都是小改动，风险低，立刻消除崩溃和刷屏
第二步：定许可 + 钉构建（决定项目能不能发版）
第三步：v0.1.3 的正确性修复 + 第一批单测
第四步：再谈 i18n / 渲染优化 / PVC
```

**验收基线**：每一步之后都应满足「`gradlew clean :fabric:remapJar` 通过 + 新增单测全绿 + 双人实测一局：加入、走子、吃子、求和、认输、掉线、旁观、聊天」。

---

## 7. 执行记录

### 7.0 版本顺序（已发布 v0.1.2，v0.1.3 在 §8 落档）

v0.1.2 已经按 §7 的原计划发布。v0.1.3（"引擎版"）是把路线图里 v0.3 的引擎项提前到
v0.1.3 一起交付 —— `ChineseChessEngine` 门面已经在 §7.3/§7.6 做好了，v0.1.3 把它
"接上**真实的消费者**"（PvcController + 测试）。原始规划里把这条逐项展开的"v0.3
引擎版"路线因此**整体不再单独排期**，被本节吸收。详见 §8。

---

## 8. v0.1.3 引擎版执行记录（本次落地）

### 8.1 新增 / 变更

| 文件 | 内容 |
|---|---|
| `pvp/PvcController.java`（新） | 电脑对手调度器，`THINK_MILLIS=600`、`MIN_REPLY_DELAY_MILLIS=400`、`IN_FLIGHT_TIMEOUT_NANOS=30s`；守护线程 `qisheng-chess-engine`，`Thread.NORM_PRIORITY - 1`，全服串行。`maybeSchedule` 是 PVC 棋盘状态变更的唯一挂点 |
| `pvp/GameSession.java` | `getPlayerAtSide(int side)` + `isComputerToMove()`：mode==PVC && state==PLAYING && 红黑**不**都空 && `getPlayerAtSide(sdPlayer)==null`。**电脑执哪边是从存进状态推导的，不入 NBT** |
| `pvp/GameLogic.java` | `tryMove` 尾部抽成 `private applyMove(session, src, dst)`（isSquare→OUT_OF_BOUNDS、canMove→ILLEGAL_MOVE、makeMove 失败→KING_EXPOSED、captured→setIrrev、翻转 sdPlayer、dst 设选中、checkGameOver）。**新增** `public tryEngineMove(session, src, dst)`：同样的三状态检查 + `if (!isComputerToMove()) return NOT_YOUR_TURN` + applyMove；**没有** SOURCE_MISMATCH（电脑没有选中状态） |
| `pvp/SessionManager.java` | `joinGame` 在 PVP 红/黑分支之前**插 PVC 分支**：坐红即开打，立即 PLAYING，人类永远执红。`takeOver` 加 `if (mode==PVC) return false` —— PVC 拒绝第二个真人 |
| `pvp/GameBroadcaster.java` | `broadcastSync` 在 `markChanged` 之后调 `PvcController.maybeSchedule`（PVC 回合切换唯一挂点） |
| `engine/ChineseChessEngine.java` | `searchBestMove(fen, depth, millis)`（私有解析 → `new Search(pos, 14).searchMain`，parseFen==null 返 0）+ `firstLegalMove(fen)`（第一个 `makeMove` 成功即 `undoMakeMove` 返回，故意弱，做兜底）。**两者都对私有棋盘操作，绝不能用调用方的 live 棋盘** |
| `block/CChessBoardBlock.java` | join 进 PLAYING 时按模式二选一提示文本（PVC 走 `qisheng.chess.pvc.started`）；`sendOpenScreen(...)` **之前**调 `PvcController.maybeSchedule(serverLevel, pos, session)` —— 右键棋盘是唯一"不改状态、因此不广播"的路径，也是重启后重启电脑的唯一时机 |
| `network/ChessInteractC2SPacket.java` | `handleJoin` PLAYING 分支按模式二选一；`findOpponent` 在 PVC 下返回 null（黑座为空），`if (opponent != null)` 自然跳过 |
| `command/ModCommands.java` | `setMode` 多发一条翻译提示，告知"全局模式已切换 + 只影响新建棋盘" |
| `lang/zh_cn.json` + `lang/en_us.json` | 各 +3 键 → **74 键/文件**：`qisheng.chess.pvc.started`、`qisheng.chess.mode.pvp`、`qisheng.chess.mode.pvc` |
| `gradle.properties` | `mod_version = 0.1.3` |

### 8.2 PvcController 的关键设计（防止"为什么这个 hook 在那里"）

1. **串行 worker**。搜索是 CPU 密集：单线程池是故意为之。一个并发搜放压力稍大，几个 PVC 棋盘足以吃满所有核心；串行让一切交给更慢的路径，不影响并发。
2. **跨线程只看 FEN**。工作线程拿到的只是 `session.getChessData().toFen()`。FEN 是不可变的，它自己解析一次送给引擎自己的 `Search`，把 `makeMove` / `undoMakeMove` 隔离在那点活棋盘上。tick 线程拿到的还是自己那份，且**以后还能用**。
4. **`IN_FLIGHT.remove(key)` 必须在任何 `broadcastSync` 之前**。`broadcastSync` 会调 `PvcController.maybeSchedule`，而 `maybeSchedule` 自己又会因为 `IN_FLIGHT` 看到“还在路上”而拒收。所以**必须在 `play` 开头就把标记删掉**，否则电脑此后再也不走子 —— 这是个反 bug：bug 是完全自洽的、不会报错、只会默默"电脑不动了"。
5. **回执最短延迟 400 ms**。`searchMain` 几乎都远不到 600 ms 就会返回 —— 没有地板，人类还没反应过来电脑就走了。
6. **棋盘寿终：把对局判为输家。** `tryEngineMove` 与 `firstLegalMove` 都返回 ILLEGAL_MOVE 时**不留死局**。唯一能走到这一步的场景是手改存档在电脑执子时让位为空 + 下法无解；判负 + FINISHED + `broadcastGameOver` 都会带走该局。

### 8.3 PvcController 测试（`PvcGameLoopTest`，15 例，类级 `@Timeout(60)`）

`searchReturnsAPlayableMove`、`fallbackMoveIsPlayable`、`unusableFenDegradesToZero`、
`computerOnlyMovesOnItsOwnTurnInPvc`、`pvpModeNeverHandsTheBoardToTheComputer`、
`computerDoesNotPlayBothSides`、`finishedGameEndsTheComputerTurn`、
`humanAndComputerCompleteOneFullTurn`、`engineCannotMoveForTheHuman`、
`engineIllegalMoveLeavesTheBoardUntouched`、`engineStopsAfterGameOver`、
`pvcJoinStartsTheGameImmediately`、`pvcBoardRefusesASecondHuman`、
`pvpJoinStillWaitsForTheOpponent`、`modeOnlyAppliesToNewBoards`。

**测试踩坑**：用 `BoardKey` / `ResourceKey` 即 `ExceptionInInitializerError` →
`ResourceKey.createRegistryKey` → `Registries.<clinit>` / `BuiltInRegistries.<clinit>` →
`MappedRegistry.<init>` → `IllegalArgumentException: Not bootstrapped (called from
registry ResourceKey[minecraft:root / minecraft:root])`。解法：类顶部静态块
`SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`（`net.minecraft.SharedConstants`
/ `net.minecraft.server.Bootstrap`），且维度键**不要**用 `Registries.DIMENSION`，改手搓：
`ResourceKey.create(ResourceKey.createRegistryKey(new ResourceLocation("minecraft","dimension")), new ResourceLocation("minecraft","overworld"))`。

`tryEngineMove(s, 0, 0)` → `OUT_OF_BOUNDS`（0 不在棋盘），要拿 `ILLEGAL_MOVE` 得用
`Position.COORD_XY(FILE_LEFT, RANK_TOP) = 51`，断言 `(s, 51, 51)`（src==dst）。

### 8.4 验收

`$env:JAVA_HOME='D:\工具\jdk-17.0.20.1+1'; .\gradlew.bat :common:compileJava :common:test :fabric:compileJava :fabric:remapJar` → **BUILD SUCCESSFUL 17s**。**51 个用例全绿**（原 36 + 新 15）。产物 `qisheng_chess-fabric-0.1.3.jar`。

### 8.5 v0.1.3 仍未决（真·遗留）

- **PVC 的电脑永远执黑**：人类永远执红，`takeOver` 与 `swapRoles` 都不动 —— 这是设计，不是 bug。
- **服务端 i18n 仍不完整**：`ChessInteractC2SPacket` 的错误提示与 `ModCommands` 的几个失败分支、以及 `GameMessages.describeSelect/describeMove` 仍是硬编码中文（仅翻译键对应 GUI 文案）。
- 对局快照随区块存档落盘，若在区块被保存前进程被杀，最近若干步可能丢失。

---

## 7. 执行记录（v0.1.2，本次实际落地）

### 7.1 构建：已可复现
最终栈 = **Gradle 9.2.1**（wrapper）+ **Architectury Loom 1.13.469** + **daemon JVM = JDK 25** + **编译 toolchain = 17** + **启动 gradlew 的 `JAVA_HOME` = JDK 17**。

| 文件 | 值 |
|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | `gradle-9.2.1-bin.zip` |
| `gradle/gradle-daemon-jvm.properties` | `toolchainVersion=25` |
| `gradle.properties` | `org.gradle.java.installations.paths=D:/\u5DE5\u5177/jdk-17.0.20.1+1` |
| `build.gradle` | `dev.architectury.loom` 版本 `1.13.469`（原来是会漂移的 `1.11-SNAPSHOT`） |
| 环境变量 | 跑构建前 `set JAVA_HOME=D:\工具\jdk-17.0.20.1+1`（**不能用 JDK 25 启动**，见下） |

为什么必须这么组合（实测，不是推测）：
- Loom 1.13 传递依赖 `net.fabricmc.unpick:unpick` 3.x，它**拒绝 JVM < 21**（`Dependency requires at least JVM runtime version 21`）；
- Gradle 8.13 / 8.14.3 **跑不了 JDK 24+**（`Unsupported class file major version 69`，Groovy 读不了 class 文件）；
- 而 Gradle 8.13 在 JDK 17 上又会撞上 `Test` 任务类型建不出来的老问题（`:common:test > Could not create task … Type T not present`）；
- ⇒ 唯一自洽解：**Gradle 9.x 运行 + 高版本 JDK 跑 daemon + toolchain 17 编译**。

已用 `gradlew wrapper` 补齐 Unix `gradlew`（原来只有 `gradlew.bat`）。
实测：`gradlew clean build` → **BUILD SUCCESSFUL**（20 tasks，含 `:common:test`），产物 `fabric/build/libs/qisheng_chess-fabric-0.1.2.jar` = **187729 字节**（v0.1.1 基线 162794；i18n 与引擎加固之前是 182414）。

#### 7.1.1 「`JAVA_HOME` 必须是 JDK 17」——一个花了很久才定位的坑（实测，别再踩）

**症状**：`gradlew :common:test` 报
`Test process encountered an unexpected problem. > Could not execute test class '…'`，
**每一个**测试类都是 `ClassNotFoundException`，而 `.class` 文件明明都在 `common/build/classes/java/test/` 里。

**根因**：类路径太长时 Gradle 把 worker 的 classpath 写进 `@C:\Users\<user>\.gradle\.tmp\gradle-worker-classpath<n>txt`。
这个文件由 **daemon** 按**它自己的默认字符集**写，而 daemon 的字符集是从**启动它的客户端 JVM** 继承的：

| 客户端 `JAVA_HOME` | 客户端 `file.encoding` | argfile 里 `代码` 的编码 | JDK 17 worker（`sun.jnu.encoding` = cp936）读到的结果 |
|---|---|---|---|
| JDK 25 | UTF-8 | `E4 BB A3 E7 A0 81`（6 字节） | 乱码 → 路径不存在 → 全部 CNFE |
| JDK 17 | GBK | `B4 FA C2 EB`（4 字节） | 正确 |

同一份 classpath 的 argfile 大小会差 **10 字节**（路径里出现 5 次 `代码` × 2 字节），这是最快的判别特征。
用 `-Dfile.encoding` 或 `jvmArgs` 都改不动：**必须在启动 `gradlew` 之前把 `JAVA_HOME` 换成 JDK 17**。
daemon 仍由 `gradle/gradle-daemon-jvm.properties` 钉在 JDK 25，所以 Loom / unpick 需要的 ≥21 不受影响 ——
**"daemon 要 ≥21" 与 "启动器要 17" 是两件事，不冲突。**

### 7.2 计划项完成情况

| 计划项 | 状态 |
|---|---|
| P0-1 引擎许可决策 | ✅ 定为 **GPL-2.0-or-later**；`LICENSE`（GPL-2.0 全文，从 gnu.org 下载）、`NOTICE`、`README.md`、`fabric.mod.json` 四处一致 |
| P0-2 钉死构建 | ✅ 见 7.1，并补了 `gradlew` |
| P0-3 清理 NeoForge | ✅ 整个 `neoforge/` 已删除，`enabled_platforms = fabric` |
| P0-4 聊天 64 字截断 | ✅ 服务端 `MAX_LEN` 64→256（`writeUtf` 限的是 **UTF-8 字节**不是字符）；客户端 `clampToWire` 按字节+字符双限 |
| P0-5 `ActionPopup` 文案 | ✅ 用上 `acceptLabel/rejectLabel` |
| P1-1 会话键加维度 | ✅ 新增 `pvp/BoardKey.java`（`dimension` + `pos`，`pos` 在紧凑构造器里 `immutable()`），`SessionManager` 全面改用 |
| P1-2 掉线流程 | ✅ `PlayerDisconnectHandler` 重写：置 `FINISHED` + `broadcastGameOver`（**原来完全没有终局广播**）+ 复位 + 掉线者与幸存者**双方**都 `evictPlayer`（原来只 evict 掉线者，幸存者会被卡住） |
| P1-3 `joinGame` 占用校验 | ✅ 一个人只能占一局 |
| P1-4 `swapRoles` 双向 | ✅ 顺序无关双向匹配 |
| P1-5 GUI 状态重建 | ✅ 弹窗栈/聊天记录/输入草稿提到 Screen 字段，`init()` 重建 |
| P1-6 弹窗清理 | ✅ `removed()` 清理 + `dismiss(Tag)` 让已取消的邀请按钮失效 |
| P1-7 命令权限 | ✅ `mode` 与新增的 `purge` 都要求 `OP_LEVEL = 2` |
| P1-8 命令坐标颠倒 | ✅ `visualToInternal` 与 `boardToAscii` 的行号对齐（**行 0 = 最下面一行**） |
| P1-9 调试刷屏 | ✅ `ChessSyncS2CPacket` 的 FEN 调试行删除；`BoardMessages.sendTo` 的整盘输出删除 |
| P2 网络收口 | ✅ `SpectatorListS2CPacket` 加 4096 上限 + 64 字节名字上限；`SwitchPackets.ResultPacket`/`PopupS2CPacket` 的 enum 越界回退；`ChessInteractC2SPacket` 硬化（action 白名单、方块类型校验先于会话查询、三种动作统一距离校验、`readableBytes()` 守卫） |
| P2 共享 buf 广播 | ✅ `GameBroadcaster` 重写，**每个收件人一个 `FriendlyByteBuf`** |
| P3 客户端 widget（P2-5 整段） | ✅ 13 项：聊天按字节截断、scissor 裁剪、滚轮方向统一、静态弹窗栈改实例态、`PlayerAvatarCache` LRU+TTL、新增 `TextSanitizer`（剥 `§`） |
| 优化计划 1 引入测试 | ✅ `common/src/test/java` + JUnit 5，**51 个用例全绿**（v0.1.2 时 36 例，v0.1.3 又增 15 例 `PvcGameLoopTest`） |
| 优化计划 2 `ChineseChessEngine` 门面 | ✅ 已完成（见 7.3） |
| 优化计划 7 i18n | ✅ 已完成：`client/` 全部用户可见文案改 `Component.translatable("qisheng.chess.*")`（63 个唯一键、84 处调用点），`zh_cn.json` / `en_us.json` 各 **71 键、键集完全相同**，java 引用的键零缺失，63 个新键的 zh 值与原中文字面量逐字一致 |
| 优化计划 8 持久化 | ✅ **已完成**（见 7.4），比原计划提前：它其实是一个"棋盘在重载世界里彻底失效"的可用性 bug，不只是"重启后对局清空" |
| 优化计划 10 引擎时间预算 | ✅ **已完成**（见 7.6）：原来单层迭代没有中断点，可以跑任意久 |

### 7.3 对原审查结论的两处修正（重要，原报告有错）

1. **`Position.fromFenString(String)` 不会返回 `null`** —— 原报告 3.1/3.2 说"畸形 FEN 失败返回 null"是**错的**。实际它**从不校验**：`fromFen` 对不认识的字符直接跳过（`g` 被忽略，而 `a r b a` 是合法字母，会凭空生成黑方棋子），空串返回**空棋盘**，`null` 直接 NPE。
   ⇒ 已补 `ChineseChessEngine.isWellFormedFen(String)`（10 段 × 每段宽度 9 × 合法字符集 × 走子方字段）与 `parseFen(String)`（不合规返回 null），并在测试里把"从不校验"这一行为固化成 characterization 测试。
2. **`legalMove` 的越界读在真实盘面上不可达** —— 它第一件事就是查 `(pcSrc & pcSelfSide) == 0 → return false`，于是 `src == dst` 必然提前返回（目标格是自己人），车/炮要越界需要整条线到 255 全空。**真正能触发 `ArrayIndexOutOfBoundsException: Index -8 out of bounds for length 7` 的入口是直接调 `Position.makeMove`（源格为空时 `delPiece(sq, 0)` → `addPiece(sq, 0, true)` → `PIECE_VALUE[-8]`）**。已由 `ChineseChessEngine.canMove/applyMove` 挡住，并有专门测试钉住。
3. `CChessBoardBlock.java` 的"使用了已过时的 API"告警来源 = 它覆写了废弃的 `BaseEntityBlock#getRenderShape()`；删除该覆写后**重跑完整构建，javac 告警已消失**。

### 7.4 对局持久化（原"优化计划 8"，v0.3 提前到 v0.1.2）

**为什么要做（真实 bug，不是打磨）**：`SessionManager` 是进程内单例，而会话只在**方块被放置**时由 `CChessBoardBlock.onPlace` 注册。服务器一重启，区块重载**不会**触发 `onPlace`，右键棋盘就走到 `sm.get(key) == null` 分支，只回一句「该棋盘无效，请重新放置。」——**存档里的棋盘全部作废**。

**设计（三条关键决策，都带理由）**

1. **快照放 `CChessTileEntity` 的 NBT，对局本体仍留在 `SessionManager`。**
   单例里才有并发语义、玩家索引和广播路径；BlockEntity 只做存档锚点。
2. **不在 `BlockEntity#load()` 里注册会话，改为惰性"认领"。**
   `load()` 在**客户端**也会跑（区块同步），在那里注册会在客户端凭空造出一堆全局会话；而且区块卸载重载会再调一次 `load()`，会把内存里**更新**的对局用**旧**快照盖掉。
   ⇒ `load()` 只反序列化到私有字段 `restored`；真正有人用棋盘时由 `ensureSession()` 认领。优先级：**内存里的会话赢**（丢弃快照）→ 认领快照 → `getOrCreate` 新建。
3. **`saveAdditional` 在会话尚未被认领时，把 `restored` 原样写回。**
   否则一次与对局无关的区块保存就会把存档里的对局悄悄抹掉。
4. **生命周期钩子从 `SERVER_STOPPING` 改到 `SERVER_STARTED`**（`QishengChess.onServerStopping()` → `onServerStarted()`）。
   `SERVER_STOPPING` 早于最终区块保存，在那里 `resetAll()` 等于**在写盘前把每一局都抹掉**。改到进入时清理，既保留"同一 JVM 连续切世界不串局"的隔离性，又不丢数据。

**接线一览**

| 文件 | 改动 |
|---|---|
| `pvp/GameSession.java` | `save()` / `fromTag(CompoundTag)`；8 个键 `Fen`/`State`/`Mode`/`Result`/`SdPlayer`/`SelectPoint`/`Red`/`Black`。**有意不存**旁观名单与两个待处理邀请（只在线时有意义） |
| `pvp/SessionManager.java` | `adopt(key, restored)`（`putIfAbsent`，内存优先）+ 私有 `sealedIndex(key, s)` 把红黑 UUID 填回 `playerInGame` |
| `tileentity/CChessTileEntity.java` | 整文件重写：`ensureSession()` / `peekSession()` / `getSession()`（兼容旧调用点）、`load`、`saveAdditional`、`getUpdateTag()` 返回**空 tag**、静态 `markChanged(level, pos)` |
| `block/CChessBoardBlock.java` | `sm.get(key)` → `ensureSession()`，"该棋盘无效"分支只作为兜底保留 |
| `pvp/GameBroadcaster.java` | `broadcastSync` 开头标脏——**mod 里每一次状态变化都汇聚到这一个方法**，所以这是唯一需要标脏的地方 |
| `QishengChess.java` / `FabricEvents.java` | `onServerStopping` → `onServerStarted`，`SERVER_STOPPING` → `SERVER_STARTED` |

**`fromTag` 的硬性契约：永不返回 null、永不抛异常**（它在区块加载路径上被调用，一个手改坏的存档不该让整个世界加载失败）。为此：
- 枚举不用 `valueOf`，走逐常量比对的泛型助手 `enumOr(Class<E>, String, E)`；
- FEN 先过 `ChineseChessEngine.isWellFormedFen`（**必须**——引擎自带的 `fromFen` 什么都接受，`"garbage"` 会安静地变成一堆黑子）；
- `SelectPoint` 先过 `isSquare`，否则 -1；
- `state == PLAYING` 但红黑都为空 ⇒ 降级 `WAITING`（防手改出的僵尸对局）。

**测试**：新增 `common/src/test/java/com/qisheng/chess/pvp/GameSessionPersistenceTest.java`，12 个用例覆盖全新/中途/终局往返、空 tag、坏枚举名、坏 FEN、`PLAYING` 无座降级、`PLAYING` 单座保活、越界选中格、旁观与待处理邀请不落盘、二次往返逐字段稳定、`fromTag` 永不返回 null。**这组测试同时确认了 MC 的 `CompoundTag` 能在 `:common:test` 里正常加载**（引擎本身是纯 Java）。

### 7.5 仍未决 / 未验证（v0.1.2 发布时视角；§8.5 列了 v0.1.3 发布后真正遗留的）

- **未做实机验证**：本次只做到"编译 + 单测 + 产物核对"，验收基线里的 **「双人实测一局：加入、走子、吃子、求和、认输、掉线、旁观、聊天」尚未执行**。这是发版前必须补的一步。
- **持久化只过了单测，没过实机**：需要实测的是"下一局 → 存档 → 重启服务器 → 右键同一棋盘 → 盘面/走子方/座位都还在，且落子后能正常写回"。特别是 `ensureSession()` 的**惰性认领**路径和 `SERVER_STARTED` 的清空时机，只有真跑一次才会暴露问题。
- `PopupOverlay.show(...)` 现在的策略是**当前 Screen 不是 `CChessBoardScreen` 时直接丢弃**（原意是修"提示条跟着玩家跑到别的棋盘 GUI"）。代价：玩家没开着棋盘 GUI 时收不到服务端 popup。可选后续：加一个有界的待投递队列。
- ~~**`BoardMode.PVC` 是死枚举**~~：v0.1.3 §8.1 已落地（`PvcController` + `isComputerToMove` + `tryEngineMove`）。
- i18n 的 `.getString()` 路径没有再过 `TextSanitizer`：那约 38 处拿的是**本模组自带的 lang 值**（信任级别等同于改造前的编译期字面量），玩家可控的名字（求和邀请、换身份、聊天、旁观名单、徽章）**全部仍然 `strip()`**，因此没有安全回归。
- v0.1.3 的 P1 项（维度键、掉线流程、命令权限、坐标）**已在 v0.1.2 一并做掉**。

### 7.6 引擎时间预算加固（原"优化计划 10"，PVC 的前置条件）

**原报告的说法需要修正**：`Search.searchMain(int depth, int millis)` **不是"从不检查 deadline"** —— 它在每层迭代结束后比较
`allMillis`（`Search.java` 的迭代循环）并 `break`。真正的缺陷是：**一次 `searchRoot(i)` 内部没有任何中断点**，
所以**单层深搜可以跑任意久**，`millis` 形同虚设。

**改动（`common/src/main/java/com/qisheng/chess/engine/xqwlight/Search.java`）**

| 位置 | 内容 |
|---|---|
| 新字段 | `private long deadline = 0L;`（绝对时刻，0 = 不限时）、`private boolean stopped = false;`（超时即锁存） |
| 新常量 | `private static final int CHECK_NODES = 1024;`（**必须是 2 的幂**，检查是 `allNodes` 的掩码）、`private static final int ABORTED = -MATE_VALUE;` |
| 新方法 | `private boolean outOfTime()`：先看 `stopped`；`deadline == 0` 返回 false；`(allNodes & 1023) != 0` 返回 false；否则读时钟并在超时时置 `stopped` |
| `searchQuiesc` / `searchFull` | `allNodes++` 之后立刻 `if (outOfTime()) return ABORTED;`；两个方法的**收尾处**各加一道 `if (stopped) return ABORTED;` |
| `searchRoot` | `pos.undoMakeMove()` 之后 `if (stopped) break;`；中止时**不调用** `setBestMove` |
| `searchMain(int depth, int millis)` | 循环前 `deadline = t + max(1, millis); stopped = false;`，`try/finally` 里复位；`allMillis` 在没进循环时补算；`mvResult <= 0` 时用 `fallbackMove()` 兜底 |
| 新方法 | `private int fallbackMove()`：遍历 `pos.generateAllMoves`，对每个 `mv` 试 `makeMove` 成功即 `undoMakeMove` 并采用 —— **保证永不返回 0/非法着法**，除非真的无子可动 |
| `getKNPS()` | `return allMillis <= 0 ? 0 : allNodes / allMillis;`（原来 `allMillis == 0` 时**除零**） |

**为什么 `ABORTED` 取 `-MATE_VALUE`**：这正是 `searchFull`/`searchRoot` 已有的"尚无最佳值"哨兵，
所以父节点会因为 `vl > vlBest` 不成立而**自然丢弃**中止的那条线，不需要额外传播标志。

**为什么中止是安全的**：逐帧核对过 `Search.java` 的所有 `return` 点 —— 每个能观察到 `stopped` 的帧，
都位于自己那次 `makeMove` 已经 `undoMakeMove` 之后，所以棋盘不会被留在半走状态；中止的搜索也**不会**写 hash 表或 killer/history（那些会活到下一层迭代并毒化它）。

**测试**：新增 `common/src/test/java/com/qisheng/chess/engine/xqwlight/SearchTimeBudgetTest.java`（5 例，`@Timeout(60)` 兜底）：
60 ms 预算要在 5 秒内返回、1 ms 极端预算仍返回合法着法（走 `fallbackMove`）、连搜 4 轮后盘面/走子方/`zobristKey`/`zobristLock`/`moveNum`/双方子力**逐字段不变**、
`searchMain(0, 30)` 不让 `getKNPS()` 除零、无预算搜索仍然正常。辅助方法 `playSomeMoves(int halfMoves)` 用"每步取第 `(i*7+k)` 个引擎接受的着法"造出一个确定性且合法的中局，避免手写 FEN 出错。

---

## 9. v0.3.1 引擎抽象 + 国际象棋执行记录

目标：把 §3 路线图里的「v0.3 引擎版」从「单一 xiangqi」扩展为「BoardVariant 注册表 + 第二棋类」，
并新增完整可玩的国际象棋子规则。GUI 仅展示「国际象棋 GUI 占位」（v0.3.2 路线）；
引擎、协议、持久化全部 variant-aware。

### 9.1 新增 / 变更

| 文件 | 内容 |
|---|---|
| `engine/BoardVariant.java`（新） | 接口：`id / displayNameKey / boardFiles / boardRanks / totalSquares / initialFen / initialState / parseState / isValidSquare / pieceAt / sideOfPiece / canMove / applyMove / toFen / sideToMove / setSideToMove / searchBestMove / firstLegalMove / isInCheck / isCheckmate / isStalemate / pieceFenChar / legalDestsBitmapSize` |
| `engine/BoardState.java`（新） | 标记接口，`engine/xqwlight/Position` 直接 `implements BoardState`（不包一层包装） |
| `engine/Move.java`（新） | `record Move(int src, int dst)` + `Move.NONE = (-1, -1)` |
| `engine/BoardRegistry.java`（新） | `DEFAULT_ID = "xiangqi"`；注册 `XiangqiVariant` + `InternationalChessVariant`；`getByIdOrDefault` 兜底未知 id 回落 xiangqi |
| `engine/xiangqi/XiangqiVariant.java`（新, 152 行） | 薄包装 xqwlight `Position`：`canMove / applyMove / toFen / isInCheck` 全部走 `ChineseChessEngine` 门面 + `Position.makeMove / legalMove`；`isInCheck` 用 save-flip-restore sdPlayer 包裹，不污染 caller state；`pieceFenChar` 不引用 `ChineseChessEngine.PIECE_LETTERS` (private)，改为 `switch (pc & 7)` 映射 将/士/象/马/车/炮 → `k/a/b/n/r/c`；`isStalemate` 永远 false |
| `engine/international/IntChessBoard.java`（新, 80 行） | `byte[64]` 棋盘；base 1..6=Pawn/Knight/Bishop/Rook/Queen/King；白=+8，黑=+16；A1=0..H8=63；`fileOf / rankOf / sq(file,rank)`；`sdPlayer / castling / enPassantSq / halfmoveClock / fullmoveNumber` |
| `engine/international/InternationalChessVariant.java`（新, 692 行） | 完整 FIDE 规则：完整 FEN parse (1-6 字段) + `isPseudoLegal` (pawn / knight / bishop / rook / queen / king + castling) + `isSquareAttacked` (pawn 2 对角 + knight 8L + sliders 8 向 + king 8 邻) + `wouldExposeKing` (move/unmove) + `makeMove` (全部副作用 + 升变自动 queen + 王车易位同时挪车 + 50 步时钟 + castling rights 更新) + `searchBestMove = firstLegalMove wrapper` (v0.3.1 不带 alpha-beta) + `encodeMove = src \| (dst<<6)`, `srcOf = mv & 0x3F`, `dstOf = (mv>>>6) & 0x3F` + `legalDestsBitmapSize()` = 8 |
| `pvp/GameSession.java` | 加 `variantId` 字段 + NBT 键 `Variant` + `getVariantId / setVariantId / getBoardState / getVariant`；`checkGameOver()` 走 `v.isCheckmate` + xiangqi 走 `isRepeat / reachMoveLimit` + international 走 `halfmoveClock>=100` + `v.isStalemate`；老存档无 TAG_VARIANT → xiangqi |
| `pvp/GameLogic.java` | `trySelect / applyMove` 走 `variant.canMove / variant.applyMove`；xiangqi 专属 `applyXiangqiIrrev` 在 captured 时 `pos.setIrrev()` |
| `pvp/GameBroadcaster.java` | `broadcastSync` + `sendOpenScreen` 末尾写 1 字节 `variantId` (UTF-8)，合法落点位图长度走 `v.totalSquares()` |
| `pvp/PvcController.java` | `searchThenPlay / play` 走 `variant.searchBestMove(fen, 19, THINK_MILLIS)` + fallback `variant.firstLegalMove(fen)`；锁定的 variantId 与 session.currentVariantId 不一致时回退 fallback（避免 variant flip 中间态） |
| `network/LegalDestsBitmap.java` | 改 totalSquares-aware：`write / read` 都接受 `int totalSquares`；保留 `XIANGQI_SIZE=256 / XIANGQI_WIRE_SIZE=32` 常量 + 无参 overload 兼容旧测试；`wireSize(totalSquares) = (totalSquares+7)/8` |
| `network/ChessSyncS2CPacket.java` | 末尾多读 1 字节 `variantId`，用 `totalSquaresFor(variantId)` 决定位图大小（xiangqi=256, international=64）；EOFException 回落 xiangqi |
| `network/ChessOpenScreenS2CPacket.java` | 同上 |
| `client/CChessBoardScreen.java` | 加 `String variantId = "xiangqi"` 字段 + 构造器重载 + `applySync(..., String variantId)` 重载 + `drawInternationalPlaceholder(gfx)`；render 里若 `"international".equals(variantId)` 走占位提示（"国际象棋 GUI 留待 v0.3.2"），否则继续 xiangqi 渲染 |
| `gradle.properties` | `mod_version = 0.3.1` |

### 9.2 关键设计（防止「为什么这个 hook 在那里」）

1. **BoardVariant 接口方法尽量与原 ChineseChessEngine 同形**（`canMove / applyMove / toFen / firstLegalMove / searchBestMove`），让 PvcController + GameLogic + GameBroadcaster 几乎不需要 if/else 就能切换棋类。
2. **BoardState 是标记接口**，`Position implements BoardState` —— 不需要包装类，避免给 `Position` 加 24 个 default 方法；GameSession 持 `BoardState`，GameLogic 用 `variant` 调方法。
3. **variantId 在 buf 末尾写**：方便旧客户端先读旧字段再 EOFException 回落 xiangqi（向后兼容）。`writeUtf` 而不是 `writeByte` —— 未来再加 `xiangqi_fork / international960 / shogi` 等也兼容。
4. **协议变体字段 + 旧 EOF 兜底双保险**：client 端 `try { buf.readUtf() } catch (EOFException) { variantId = "xiangqi"; }` —— 不需要改旧 jar 即可玩。
5. **`wouldExposeKing` 不处理 castling 的 rook 移动**：因为王车易位的安全性（王不穿过 f1 的被将）已经在 `kingMoveOrCastle` 里用 `isSquareAttacked(b, src+step, opp)` 提前检查了；`wouldExposeKing` 只看「落子后己王被将」，王车易位单独走 `kingMoveOrCastle` 路径，所以 canMove 仍正确。
6. **`PvcController.play` 锁 variantId 一致性**：`session.getVariantId()` 在 worker 排队期间可能 flip，回退到 `firstLegalMove(fen)` 而不是直接 `searchBestMove`，避免拿到错棋类的结果。`IN_FLIGHT.remove(key)` 仍在 `broadcastSync` 之前。

### 9.3 测试（共 19 条新增 + 60 既有 = 79 条全绿）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `XiangqiVariantTest` | 9 | ID 稳定 / 9×10=90 / INIT FEN round-trip / Position implements BoardState / canMove 通过合法车 + 红车不能斜走 / applyMove 翻 sdPlayer / isInCheck 不污染 caller state / firstLegalMove 至少一个 / searchBestMove 合法着法 / xiangqi hasNoStalemate |
| `InternationalChessVariantTest` | 11 | ID / 尺寸 / INIT_FEN round-trip / 初始 16/16 pieces / malformedFenReturnsNull（7 个错 FEN）/ initialNotInCheck / 白方 O-O + castling rights 清除 / 吃过路兵 / 升变后 FEN 头 `Q3k3/8/...` / Scholar's Mate（`r1bqk2r/pppp1Qpp/...` → `isCheckmate` true）/ 50 步和棋（halfmoveClock>=100 → `isStalemate` true）/ firstLegalMove 在合法局面返合法 Move，在 mate 局面返 Move.NONE |
| `LegalDestsBitmapTest` | 6 | totalSquares-aware write/read round-trip + 边界（0, 1, 255, 256, 全 true, 全 false, 跨字节位）+ 拒绝 null |

**测试踩坑**：
- `Position.SIDE_TAG` 是 **static METHOD** 不是 field：`SIDE_TAG(0)=8, SIDE_TAG(1)=16`。调用方式 `Position.SIDE_TAG(pos.sdPlayer)`。
- `Position.sdPlayer` 语义：0=红 1=黑，INIT 默认 0。INIT FEN 无 side 后缀时 sdPlayer 留 0，Position.toFen() 带 ' w'/' b' 后缀。
- 测试 sq helper：`rank + RANK_TOP`（rank 0 = 黑顶, rank 9 = 红底，与 FEN 第一段视角一致）。
- 升变 FEN 黑王在 a8 挡 pawn forward：`k7/P7/...` → 改 `4k3/P7/...`（黑王挪 e8）。
- 升变后 toFen rank 8 = "Q3k3"（升变后 pawn 变 Q，黑王不动）。
- 王车易位 toFen assertion：整 FEN 含白王字符 `K`，`assertFalse(toFen.contains("K"))` 是 test bug —— 改取 `parts[2]`（castling field）检查 `!contains("K")` 且 `!contains("Q")`。
- Scholar's Mate FEN：`r1bqk2r/pppp1Qpp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQKBNR b KQkq - 0 4`（Qxf7# 真将死局面）。
- `LegalDestsBitmap.write(buf, dests)` 单参 overload：先 null check 再 length 检查（先访问 length 会 NPE）。

### 9.4 验收

```
$env:JAVA_HOME='D:\工具\jdk-17.0.20.1+1'; .\gradlew.bat :common:compileJava :common:test :fabric:compileJava :fabric:remapJar
BUILD SUCCESSFUL 23s (4 executed, 8 up-to-date)
```

**79 个用例全绿**。产物 `qisheng_chess-fabric-0.3.1.jar`（jar 命名需 `mod_version=0.3.1`）。

### 9.5 v0.3.1 仍未决（v0.3.2 路线）

- **国际象棋 GUI 仅占位提示**：真实棋盘 + 棋子 + 升变选择器 / 走法预览留待 v0.3.2。
- **国际象棋引擎无 alpha-beta**：搜索只是 `firstLegalMove` wrapper，v0.3.2 引入 negamax + transposition table。
- **协议 variantId 字段加在 buf 末尾**：旧 v0.6 客户端读 `readUtf()` 会 EOFException，已加 try/catch 兜底。
- **服务端没有实机验证**（容器无 LWJGL Display + 无 EULA TTY）—— 国际象棋 / 占位 GUI 真实表现以实机为准。

---

## 10. v0.4.0 — 五子棋 + 围棋 + 独立方块（执行记录）

### 10.1 文件清单

#### 新文件

| 路径 | 用途 |
|---|---|
| `common/.../block/AbstractChessBoardBlock.java` | 抽 use / onPlace / onRemove / FACING / rotate / mirror / newBlockEntity 共享逻辑; abstract `getVariantId()`; 3 个静态属性 helper |
| `common/.../block/GomokuBoardBlock.java` | 五子棋棋盘方块, `extends AbstractChessBoardBlock` |
| `common/.../block/GoBoardBlock.java` | 围棋棋盘方块, `BOARD_SIZE = IntegerProperty.create("size", 9, 19)`, `getVariantId(BlockState)` 按 size 路由 |
| `common/.../engine/gomoku/GomokuBoard.java` | 五子棋状态: `byte[225]`, `sdPlayer`, `moveCount`, `winner` |
| `common/.../engine/gomoku/GomokuVariant.java` | 15×15 规则, FEN dialect `<15 ranks> <turn>`, 5+ 胜 |
| `common/.../engine/go/GoBoard.java` | 围棋状态: `byte[size*size]`, `sdPlayer`, `blackCaptures/whiteCaptures`, `koSquare`, `passes`, `finished` |
| `common/.../engine/go/GoVariant.java` | 9 路 + 19 路两个静态实例, KOMI=7.5, 中国数子, 自杀禁手, 简版 ko |
| `common/src/test/.../engine/gomoku/GomokuVariantTest.java` | 19 条用例 |
| `common/src/test/.../engine/go/GoVariantTest.java` | 21 条用例 |

#### 修改文件

| 路径 | 变更 |
|---|---|
| `common/.../engine/BoardVariant.java` | 加 3 helper: `indexForFileRank(file, rank)` / `fileOf(sq)` / `rankOf(sq)` |
| `common/.../engine/BoardRegistry.java` | 注册 5 变体 (xiangqi + international + gomoku + go9 + go19) |
| `common/.../engine/xiangqi/XiangqiVariant.java` | 实现 3 helper (走 Position.COORD_XY) |
| `common/.../engine/international/InternationalChessVariant.java` | 实现 3 helper (走 IntChessBoard.sq) |
| `common/.../block/CChessBoardBlock.java` | 瘦身到 15 行, `extends AbstractChessBoardBlock` |
| `common/.../block/ModBlocks.java` | 注册 CCHESS + GOMOKU + GO |
| `common/.../block/ModBlockEntities.java` | 单 `BlockEntityType` 绑定 3 个 Block |
| `common/.../tileentity/CChessTileEntity.java` | `ensureSession(String preferredVariantId)` overload |
| `common/.../pvp/GameSession.java` | `boardState: BoardState` 字段 + `getChessData()` instanceof fallback |
| `common/.../command/ModCommands.java` | doSelect / doReset / doLeave 改走 variant API |
| `common/.../client/CChessBoardScreen.java` | GUI variant-aware (cols/rows 动态 + 简化五子棋 / 围棋渲染) |
| `common/.../lang/zh_cn.json` + `en_us.json` | 加 5 变种名 + 3 方块名 (122→133 键) |
| `gradle.properties` | `mod_version=0.3.1 → 0.4.0` |

### 10.2 关键设计

#### 抽象方块基类 vs 三个具体子类

- `AbstractChessBoardBlock` 抽共享 use/onPlace/onRemove/FACING/rotate/mirror/newBlockEntity/Properties 帮助。三个具体子类只实现 `getVariantId()` (and GoBoardBlock 多一个 `getVariantId(BlockState)` 按 size 路由)。
- `ModBlocks` 用同一 `Block.Properties` 模式: `mapColor + strength(2.0f) + sound(WOOD) + noOcclusion()`。围棋 / 五子棋换不同的 mapColor 让方块在世界里有视觉区分。

#### GameSession 重构

- `private final Position chessData` → `private BoardState boardState` (variant-specific)。
- `getChessData()` 改为 `return boardState instanceof Position p ? p : null` — xiangqi 路径保留兼容, 其它变种返 null。
- `setVariantId(id)` 在变种变化时重置 boardState。
- `save()` / `fromTag()` 走 `variant.toFen / variant.parseState`。
- `checkGameOver()` 走 v.isCheckmate + xiangqi 走 isRepeat/reachMoveLimit + international 走 halfmoveClock>=100 + v.isStalemate。

#### GUI 简化渲染

- 五子棋: 15×15 网格 + 黑/白圆子, 无坐标、无提示窗。
- 围棋: 网格 + star points (9×9 中心 / 19×19 9 点) + 黑/白圆子。
- 单击路由: xiangqi 选子→落子两步, 五子棋/围棋 单击直接 `ACTION_MOVE(src==dst)` (placement semantics)。
- `drawStone` 共享工具: 黑/白圆子 + 灰色描边。

### 10.3 测试

| 测试类 | 用例数 |
|---|---|
| engine.gomoku.GomokuVariantTest | 19 |
| engine.go.GoVariantTest | 21 |
| engine.international.InternationalChessVariantTest | 11 |
| engine.xiangqi.XiangqiVariantTest | 10 |
| engine.ChineseChessEngineTest | 8 |
| engine.xqwlight.PositionTest | 11 |
| engine.xqwlight.SearchTimeBudgetTest | 5 |
| network.LegalDestsBitmapTest | 6 |
| pvp.GameSessionPersistenceTest | 13 |
| pvp.PvcGameLoopTest | 15 |
| **总计** | **119** |

- 119 tests PASSED, 0 failures, 0.954s。
- 关键测试: Gomoku 4 方向 5 连 (H/V/diag1/diag2), Go 9+19 不同 size 复用同一 API, 自杀禁手, 简单 ko guard, 2 passes 终止游戏, scoreDelta 中国数子规则。

### 10.4 验收

- `gradlew.bat :common:compileJava :common:test :fabric:compileJava :fabric:remapJar` → **BUILD SUCCESSFUL 21s**。
- jar = `qisheng_chess-fabric-0.4.0.jar` = 239416 B (vs 0.3.1 = 239271 B, +145 B)。

### 10.5 v0.4.0 仍未决（v0.4.2 路线）

- **五子棋 / 围棋 GUI 是简化功能性渲染**：无坐标引导、无禁手标记（Renju / Sanrensei）、无走法计数、无计时器；下一步 (v0.4.2) 加星位高亮 + 禁手点红 + last-move 标记。
- **PVC 电脑仅五子棋 / 围棋 / 国际象棋都是 `firstLegalMove`**：真要下出水平得引入 alpha-beta (国际象棋) / pattern-based defense (五子棋) / heuristic scoring (围棋)。v0.4.2 引入五子棋 AI (3-move threat space search)。
- **围棋 GUI 无 pass 按钮**：当前 `applyPass` 走 `Move(-1,-1)` sentinel 但 GUI 没有"虚着"按钮；v0.4.2 在 action panel 加 pass。
- **围棋无 superko**：当前仅简单 ko (上一个落子位置回放禁手)；v0.4.2 引入 positional superko (重复局面禁手)。
- **服务端没有实机验证**：容器无 LWJGL Display + 无 EULA TTY —— 五子棋 / 围棋实际表现以实机为准。
- **国际象棋 GUI 仍占位提示**（v0.3.1 仍未决）：完整 64 格 + 升变选择器 / 走法预览留待 v0.4.2 + v0.5.0。

