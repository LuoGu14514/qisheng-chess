# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
详细的架构分析与后续规划见 [REVIEW-AND-PLAN.md](REVIEW-AND-PLAN.md)。

---

## [Unreleased] — v0.2 体验版 i18n 收尾

目标：把所有 server 端硬编码中文一并搬进 lang 文件，让 `en_us` 也能完整跑。

### 变更

#### 服务端 i18n 完整化

- `pvp/GameMessages.java` 全部 9 个玩家提示重写为 `Component.translatable`：`server.select.*` × 8（OK / 空方格 / 棋子对方 / OUT_OF_BOUNDS / NOT_YOUR_TURN / NOT_IN_GAME / GAME_FINISHED / GAME_NOT_PLAYING）、`server.move.*` × 6（OK / SOURCE_MISMATCH / ILLEGAL_MOVE / KING_EXPOSED / NOT_IN_GAME / GAME_FINISHED）、`server.joined_red` / `server.already_joined_*` × 2。`alreadyJoinedPlaying` 签名改为 `(Component role, Component turn)` 让嵌套角色按客户端 locale 自动渲染。
- `network/ChessInteractC2SPacket.java` 6 处 `Component.literal` + 3 处"红方(先手)/黑方(后手)/红方走子/黑方走子"拼接 + 1 处玩家名嵌入，全部改 `translatable`。新增内部 helper `roleLabel(session, UUID)` + `turnLabel(session)` 让服务端能直接吐出已本地化的 `Component`。
- `command/ModCommands.java` 14 处 player-facing 路径（`sendFailure` + `sendPopupTo`）+ 4 处 admin/operator 状态输出走 `translatable`，关键修复：`doTakeover` 的 `roleName` 参数从 `String "红方"/"黑方"` 改成 `Component.translatable("qisheng.chess.role.red/black")`，英文玩家不再看见中文字面量。`setMode` 把原来的"全局模式已设为 PVP/PVC"用 `cmd.mode.changed` + `cmd.mode.label_pvp/pvc` 两条新键重构。
- `lang/zh_cn.json` 与 `lang/en_us.json` 各新增 31 个键 → **119/119 完全对称**（`cmd.mode.*` × 3 + `server.*` × 22 + `cmd.leave/board/status/takeover.*` 已经在 v0.2 中前序落地）。全仓库 `translatable("qisheng.chess.*")` 引用键集与 lang 文件键集双向差集为 0。
- 残余硬编码中文仅限于 `[qisheng]` 前缀的 admin/operator 反馈（`ModCommands:137/152/181/307`），保留作为运维输出。

#### 已知限制 v0.2 仍未决

- 棋盘不可旋转。
- 棋盘 GUI 没有走子动画、音效、最近一步高亮。
- 服务端没有实机验证（容器无 LWJGL Display + 无 EULA TTY）。

---

## [0.1.3] — 引擎版

目标：让人机模式真的跑起来，并把"v0.3 引擎版"路线里的最后几项（门面、持久化、PVC）
全部交付。引擎健壮性的 0.1.2 修复在 0.1.2 段落；本版补上其**消费者**。

### 新增

- **`pvp/PvcController.java`** —— 电脑对手的回合调度器。`maybeSchedule` 是 PVE 棋盘
  状态变更的唯一挂点：`GameBroadcaster.broadcastSync` 之后无脑调一次，引擎在
  工作线程上跑搜索（不死板），一路返回 tick 线程走子。
  - **串行 worker**（`Executors.newSingleThreadExecutor`，守护线程
    `qisheng-chess-engine`，`Thread.NORM_PRIORITY - 1`）：搜索是 CPU 密集，
    几个 PVC 棋盘足以吃满所有核心；串行让一切交给更慢的路径，
    不影响并发。
  - **跨线程只看 FEN**：工作线程拿到的只是 `session.getChessData().toFen()`，
    自己解析一次送给引擎自己的 `Search`。活棋盘留在 tick 线程，不跨边界。
  - **回执最短延迟 400 ms**：避免同帧出现两连动，人类看不到自己的走子。
  - **`IN_FLIGHT` 防重入**：每个棋盘的 `Map<BoardKey, Long>` 标记，30 s 超时后
    可重新排队；如果该标记异步到来，重入会被自己的标记挡掉，电脑
    此后再也不走子。所以**必须在 `broadcastSync` 之前**先 `remove(key)`。
- **`ChineseChessEngine.searchBestMove(fen, depth, millis)` + `firstLegalMove(fen)`**：
  - `searchBestMove` 是门面化的搜索入口；私有解析 → `new Search(pos, 14).searchMain(depth, millis)`。
  - `firstLegalMove` 是**故意弱**的兜底——`generateMvs` 是搜索自身的
    生成顺序，第一个 `makeMove` 成功的就返回。`searchBestMove` 返回 0 或抛异常时
    用来保底。
- **`GameSession.getPlayerAtSide(side) / isComputerToMove()`**：
  - 电脑执哪一边**不入 NBT**，从存进状态推导：PVC 模式 + PLAYING 状态 +
    未双空 + `getPlayerAtSide(sdPlayer) == null`。
  - **「双空」守卫防存档作弊**：手改的存档让电脑下自己是不可能的（不会
    出现红黑都空的状态）。
- **`GameLogic.tryEngineMove(session, src, dst)`**：`tryMove` 没有
  角色检查（因为电脑不坐那个），它的
  角色门**改用** `isComputerToMove()` 替。**不要**有
  `SOURCE_MISMATCH` 检查（电脑没有选中状态）。

### 变更

- **`SessionManager.joinGame` 在 PVP 红/黑分支之前插 PVC 分支**：
  PVC → 坐红即开打 → PLAYING 立即（人类永远执红，黑座 = 电脑）。
- **`SessionManager.takeOver` PVC 拒收**：一个 PVC 棋盘就是
  人 vs 电脑，拒绝第二个真人接手（不转成 PVP）。
- **`/qisheng mode pvp|pvc` 多发一条翻译提示**，告知"全局模式已切换
  + 只影响新建棋盘"。
- 新增 3 个翻译键：`qisheng.chess.pvc.started`、`qisheng.chess.mode.pvp`、
  `qisheng.chess.mode.pvc`；两个语言文件各 **74 个键**，键集仍然完全一致。
- `GameBroadcaster.broadcastSync` 之后调用 `PvcController.maybeSchedule`（PVC 回合切换
  唯一挂点）。`CChessBoardBlock.use` 在 `sendOpenScreen` 之前调用
  同一个 `maybeSchedule`（右键棋盘是唯一"不改状态、因此不广播"的路径，也是
  重启后重启电脑的唯一时机）。
- 引擎调用点全部走**门面**：`CChessBoardScreen` 渲染合法落点时用
  `ChineseChessEngine.canMove`，`PvcController` 用 `searchBestMove` / `firstLegalMove`。
- **测试 +15 → 51 全绿**：`PvcGameLoopTest`（15 例）覆盖 PVC / PVP 区分、
  一来一回的完整回合、引擎拒绝/兜底/恢复、`joinGame` 立即开局、`takeOver` 拒收、
  `mode` 只影响新建棋盘。

### 已知限制

- **PVC 的电脑永远执黑**：人类永远执红，`takeOver` 与 `swapRoles` 都不动。
- **没有取消思考**：人类落子后电脑才起步，可中断；玩家没在棋盘
  GUI 时收不到服务端弹窗。
- **服务端 i18n 仍不完整**：`ChessInteractC2SPacket` 与 `ModCommands`
  的错误提示与 `GameMessages.describeSelect/describeMove` 仍是硬编码中文。
- 客户端界面英文翻译尚未经母语者校对，是逐句直译。
- 对局快照随区块存档落盘，若在区块被保存前进程被杀，最近若干步可能丢失。

---

## [0.1.2] — 止血版

目标：**可复现的构建 + 不崩 + 不刷屏 + 多人多局不串**。
（原规划的 v0.1.2「止血」与 v0.1.3「正确性」两批改动在本版一并发布。）

### 修复

**服务端 / 逻辑**
- **多棋盘串台**：会话原来用 `BlockPos` 作全局键，两个维度里同一坐标的棋盘会互相覆盖。新增 `pvp/BoardKey.java`（`dimension` + `pos`），`SessionManager`、`CChessTileEntity`、命令与全部网络包都改用它。
- **广播共享缓冲区**：`GameBroadcaster` 原来把同一个 `FriendlyByteBuf` 发给多个收件人，除第一个玩家外收到的都是坏数据。改为每个收件人各建一个缓冲区。
- **终局后无法重开**：棋盘进入 `FINISHED` 后只能靠命令复位。现在右键已结束的棋盘 = 复位并开新局。
- **掉线流程**：对局中掉线原来只在部分路径上处理，幸存者会被自己那一局挡住无法加入新局。现在掉线即判负：置 `FINISHED` → 广播终局 → 通知幸存者 → 复位 → **双方**都清出索引 → 重新同步。
- **`joinGame` 无占用校验**：一个玩家可以同时占住多局。现在一人只能在一局里。
- **`swapRoles` 顺序敏感**：交换红黑只在参数顺序恰好匹配时生效。改为顺序无关的双向匹配。
- **重开一局不清理会话**：`SessionManager` 现在有 `resetAll()`，由 `ServerLifecycleEvents.SERVER_STARTED` 调用，存档切换不再残留旧局。**注意是 STARTED 而不是 STOPPING**：会话正是要写进棋盘 NBT 的东西，而 `SERVER_STOPPING` 早于最终区块保存，在那里清空等于在写盘前把每一局都抹掉。
- **棋盘在重载世界里彻底失效**：`SessionManager` 是进程内单例，而会话只在**方块被放置**时（`onPlace`）注册。服务器一重启，区块重载**不会**触发 `onPlace`，于是右键棋盘只会得到「该棋盘无效，请重新放置。」——存档里的棋盘全废。现在整局状态持久化到棋盘方块自己的 NBT，首次被使用时惰性恢复（见「新增」）。

**命令**
- `/qisheng mode` 与新增的 `/qisheng purge` 现在要求**管理员权限（等级 2）**——原来任何玩家都能改对局模式、清空所有会话。
- `/qisheng <sq> <sq>` 的坐标与 `boardToAscii` 打印的行号**上下颠倒**。已对齐：**行 0 = 最下面一行**（红方底线）。
- `/qisheng leave` 在对局中离场现在判负（原来只是静默离开，对手永远等不到结果）。

**网络 / 安全**
- `ChessInteractC2SPacket` 硬化：动作白名单校验、**先校验该坐标确实是棋盘方块再查会话**（原来可以对着任意 `BlockPos` 触发逻辑）、三个动作统一做距离校验、`readableBytes()` 守卫后再读载荷。
- `SpectatorListS2CPacket`：旁观者数量加 4096 上限、名字加 64 字节上限（原来恶意/异常长度可直接撑爆客户端）。
- `SwitchPackets.ResultPacket` 与 `PopupS2CPacket` 的枚举序号越界现在回退到安全值，不再抛 `ArrayIndexOutOfBoundsException`。
- 聊天长度上限 64 → 256。注意 `writeUtf` 限的是 **UTF-8 字节**：64 字节只够约 21 个汉字，超出会让客户端抛 `EncoderException` 崩游戏。客户端同步按字节截断。

**客户端**
- 消息条（芯片）渲染的是原始 NBT 而不是文本。
- 缩放窗口（`init()` 重跑）会清空聊天记录与输入草稿；弹窗栈是 `static`，跨局残留且永不清理；已取消的邀请按钮仍然可点。全部改为 Screen 实例状态，并在 `removed()` 里清理。
- 聊天文本无长度上限、无裁剪，长行会溢出边框。现在按字节截断并用 scissor 裁剪。
- 两处滚轮方向相反。
- 点击棋盘时因提前 `return` 导致聊天框永远不失焦。
- 玩家名里的 `§` 格式化代码未过滤，可用来伪造其他玩家的名字样式。
- `PlayerAvatarCache` 永久缓存且无上限 → 改为 LRU 128 条 + 5 分钟 / 30 秒两级 TTL。

**调试残留**
- `ChessSyncS2CPacket` 每次同步都往聊天栏打 `§7[启升棋同步] … FEN=…`。
- `BoardMessages.sendTo` 每次操作都打印整盘 ASCII 棋盘。均改为只在 `/qisheng board` 显式请求时输出。

**引擎健壮性（新增 `engine/ChineseChessEngine.java`）**
- 内置的 xqwlight 引擎**完全没有输入校验**：`Position.makeMove` 对空源格会抛 `ArrayIndexOutOfBoundsException: Index -8 out of bounds for length 7`；`Position.IN_BOARD(-1)` 直接越界读表；`Position.fromFenString` **从不失败**——畸形输入会静默变成一堆凭空生成的棋子，空串变成空棋盘，`null` 变成 NPE。
- 新增受检门面 `ChineseChessEngine`：`isSquare` / `pieceAt` / `isOwnPiece` / `canMove` / `applyMove` / `isWellFormedFen` / `parseFen`，对任意 `int` 输入都返回布尔值而不抛异常。`GameLogic` 与棋盘 GUI 已改走门面。
- **引擎搜索没有真正的时间预算**：`Search.searchMain(depth, millis)` 只在**每层迭代之间**比较时钟，一次 `searchRoot` 内部没有任何中断点，所以单层深搜可以跑任意久——`millis` 形同虚设。（这是实现人机模式的前置缺陷。）现在预算被下推到 `searchFull` / `searchQuiesc`：每 1024 个节点读一次时钟（`CHECK_NODES` 必须是 2 的幂），超时即锁存 `stopped`，让整棵递归返回 `ABORTED`（= `-MATE_VALUE`，正好是父节点已有的"尚无最佳值"哨兵，于是中止的那条线会被自然丢弃）。中止的帧一律**不写** hash 表与 killer/history，也不会把棋盘留在半走状态；`searchMain` 用 `fallbackMove()` 兜底，保证**永不返回 0 或非法着法**（除非真的无子可动）。
- **`Search.getKNPS()` 除零**：`allMillis == 0` 时抛 `ArithmeticException`，改为返回 0。

### 变更

- **平台**：删除整个 `neoforge/` 目录（其入口类语法都不完整），只保留 Fabric。`enabled_platforms = fabric`。
- **构建栈**：Gradle 8.13 → **9.2.1**；Loom `1.11-SNAPSHOT`（会漂移）→ **1.13.469**；daemon JVM 固定为 **JDK 25**，编译 toolchain 仍是 **17**（Minecraft 1.20.1 的要求）。三者互相牵制，理由见 `gradle.properties` 里的注释与 README。补齐了 Unix `gradlew`。
  - **启动构建的 `JAVA_HOME` 必须是 JDK 17，不能是 JDK 25**：Gradle 会把 worker 的 classpath 写进一个 `@argfile`，编码取自 daemon 的默认字符集，而 daemon 的字符集继承自启动它的客户端 JVM。JDK 25 客户端 → UTF-8 文件，但真正跑测试的 JDK 17 worker 按 `sun.jnu.encoding`（本机 cp936）解析，于是本项目路径里的 `代码` 变乱码，**每个测试类都报 `ClassNotFoundException`**。改用 JDK 17 启动后两端都是 GBK，问题消失；daemon 仍由 `gradle-daemon-jvm.properties` 钉在 JDK 25，Loom 需要的 ≥21 不受影响。详见 `REVIEW-AND-PLAN.md` 7.1.1。
- **许可**：`MIT` → **`GPL-2.0-or-later`**。内置引擎是 GPLv2，与 MIT 不兼容；TLM 素材是 CC BY-NC-SA 4.0，仅限非商业使用。详见 `NOTICE`。
- **文案**：`ActionPopup` 现在真正使用传进来的 `acceptLabel` / `rejectLabel`（原来无论传什么，按钮永远是「接受 / 拒绝」）。

### 新增

- `engine/ChineseChessEngine.java` —— 引擎受检门面。
- **对局持久化**：`GameSession.save()` / `GameSession.fromTag(CompoundTag)` 把整局状态序列化进 `CChessTileEntity` 的 NBT（FEN、状态、模式、结果、走子方、选中格、红黑玩家 UUID）。`SessionManager.adopt()` 负责把恢复出来的会话装回全局表并**重建玩家→棋盘的索引**（否则重启后回来的玩家不被认作已就座）。恢复是惰性的：`BlockEntity#load()` 只反序列化到内存字段，真正被别人使用棋盘时才认领——`load()` 在客户端也会跑，而且区块卸载重载会再调一次，用它注册会话会在客户端凭空造局、并用旧快照盖掉内存里更新的对局。
- `common/src/test/java/` —— **单元测试（JUnit 5，36 个用例全绿）**，覆盖开局 FEN 往返、44 步开局着法、`makeMove`/`undoMakeMove` 对称性、越界行为、门面的全域性与 FEN 校验、持久化契约（损坏枚举 / 损坏 FEN / 空 tag / 越界选中格下的降级行为，并要求 `fromTag` 永不返回 null、永不抛异常），以及搜索的时间预算（60 ms 与 1 ms 两档都必须返回合法着法、连搜 4 轮后盘面逐字段不变、零深度搜索不除零）。引擎是纯 Java，可脱离 Minecraft 测试。
- `assets/qisheng_chess/lang/en_us.json` —— 英文语言文件（同时删掉 14 个已失效的 `item.qisheng_chess.piece_*` 孤儿键）。
- **界面文案全部本地化**：客户端所有用户可见文本不再硬编码中文，改为 `Component.translatable("qisheng.chess.*")`。两个语言文件各 71 个键、键集完全一致，中英同步。日志与异常消息保持原样（只给开发者看）。
- `LICENSE` / `NOTICE` / `README.md` / `CHANGELOG.md`。
- `/qisheng purge`（仅管理员）。

### 已知限制

- **尚未做双人实机联机验证**。本版只完成到"编译通过 + 单元测试全绿 + 产物核对"。
- `PopupOverlay` 只在当前界面是棋盘 GUI 时显示提示；玩家没开着棋盘界面时收不到服务端弹窗。
- 客户端界面**英文翻译尚未经母语者校对**，是逐句直译。
- `BoardMode.PVC`（人机）仍不可用：搜索的时间预算已经补上，但**还没有任何代码消费这个枚举**——没有电脑对手、没有回合调度，`/qisheng mode pvc` 目前只切换一个无人读取的模式标记。
- 对局快照随区块存档落盘，若在区块被保存前进程被杀，最近若干步可能丢失。

---

## [0.1.1] 及更早

无正式记录。此前的开发在构建配置上有若干已知问题（会漂移的 Loom 快照版本、缺失的 JDK 路径配置、残缺的 NeoForge 平台），均已在 0.1.2 中清理。
