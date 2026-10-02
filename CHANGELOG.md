# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
详细的架构分析与后续规划见 [REVIEW-AND-PLAN.md](REVIEW-AND-PLAN.md)。

---

## [0.3.1] — 引擎抽象 + 国际象棋

目标：把 v0.3 引擎路线从「单一 xiangqi」扩展为「BoardVariant 注册表 + 第二棋类」，并新增完整可玩的国际象棋子规则。GUI 仅展示「国际象棋 GUI 占位」(v0.3.2 路线)；引擎、协议、持久化全部 variant-aware。

### 变更

#### BoardVariant 接口与注册表

- 新增 `engine/BoardVariant` 接口：`id / displayNameKey / boardFiles / boardRanks / totalSquares / initialFen / initialState / parseState / isValidSquare / pieceAt / sideOfPiece / canMove / applyMove / toFen / sideToMove / setSideToMove / searchBestMove / firstLegalMove / isInCheck / isCheckmate / isStalemate / pieceFenChar / legalDestsBitmapSize`。
- `BoardState` 是标记接口，`engine/xqwlight/Position` 直接 `implements BoardState`（不包一层包装）。
- `Move` 是 record `(int src, int dst)` + `Move.NONE = (-1, -1)`。
- `engine/BoardRegistry.DEFAULT_ID = "xiangqi"`，注册 `XiangqiVariant` + `InternationalChessVariant`；`getByIdOrDefault` 兜底未知 id 回落 xiangqi。

#### XiangqiVariant（迁移层）

- `engine/xiangqi/XiangqiVariant.java` 薄包装 xqwlight `Position`：`canMove` / `applyMove` / `toFen` / `isInCheck` 全部走 `ChineseChessEngine` 门面 + `Position.makeMove` / `legalMove`；`isInCheck` 用 save-flip-restore sdPlayer 包裹，不污染 caller state。
- `pieceFenChar` 不引用 `ChineseChessEngine.PIECE_LETTERS` (private)，改为 `switch (pc & 7)` 映射 将/士/象/马/车/炮 → `k/a/b/n/r/c`。
- `isStalemate` 永远 false（xiangqi 无逼和判定，逼和靠 `isMate` + `repStatus`）。

#### InternationalChessVariant（全新）

- `engine/international/IntChessBoard.java` (80 行): `byte[64]` 棋盘；base 1..6=Pawn/Knight/Bishop/Rook/Queen/King；白=+8，黑=+16；A1=0..H8=63；`fileOf/rankOf/sq(file,rank)`；`sdPlayer/castling/enPassantSq/halfmoveClock/fullmoveNumber`。
- `engine/international/InternationalChessVariant.java` (692 行) 完整实现：
  - 完整 FEN parse (1-6 字段任意子集)
  - `isPseudoLegal` 走 pawnPseudoLegal / knightL / bishopClearPath / rookClearPath / queenClearPath / kingMoveOrCastle
  - `kingMoveOrCastle` 检查王车易位: rank 一致 + 双方 rights mask + 车在 rookSq + 路径 f1/g1 空 + 王不在被将 + 不穿过被将 + 不停在被将
  - `isSquareAttacked`: pawn (2 对角) + knight (8 L) + sliders (8 向) + king (8 邻)
  - `wouldExposeKing` 在 move/unmove 后看自王是否被将
  - `makeMove` 全部副作用: 升变自动 queen + 王车易位同时挪车 + 双吃过路兵清掉被吃的 pawn + 50 步时钟 + castling rights 更新
  - `searchBestMove` 是 `firstLegalMove` 的薄包装 (v0.3.1 不带 alpha-beta, v0.3.2 引入)
  - `encodeMove = src | (dst<<6)`, `srcOf = mv & 0x3F`, `dstOf = (mv>>>6) & 0x3F`
  - `legalDestsBitmapSize()` = 8

#### variant-aware 重构

- `pvp/GameSession`: 新增 `variantId` 字段 + NBT 键 `Variant` (`save` 写、`fromTag` 读; 旧存档无 TAG_VARIANT → 回落 xiangqi); 新增 `getVariantId / setVariantId / getBoardState / getVariant` API; `checkGameOver()` 走 `v.isCheckmate` + xiangqi 走 `isRepeat / reachMoveLimit` + international 走 `halfmoveClock>=100` + `v.isStalemate`。
- `pvp/GameLogic.trySelect / applyMove` 走 `variant.canMove / variant.applyMove`; xiangqi 专属 `applyXiangqiIrrev` 在 captured 时 `pos.setIrrev()`。
- `pvp/GameBroadcaster.broadcastSync` + `sendOpenScreen` 末尾写 1 字节 `variantId` (UTF-8),合法落点位图长度走 `v.totalSquares()`。
- `pvp/PvcController.searchThenPlay / play` 走 `variant.searchBestMove(fen, 19, THINK_MILLIS)` + fallback `variant.firstLegalMove(fen)`; 锁定的 variantId 与 session.currentVariantId 不一致时回退 fallback (避免 variant flip 中间态)。

#### 网络协议

- `network/LegalDestsBitmap` 改 totalSquares-aware: `write / read` 都接受 `int totalSquares`; 保留 `XIANGQI_SIZE=256 / XIANGQI_WIRE_SIZE=32` 常量 + 无参 overload 兼容旧测试; `wireSize(totalSquares) = (totalSquares+7)/8`。
- `network/ChessSyncS2CPacket` + `ChessOpenScreenS2CPacket` 末尾多读 1 字节 `variantId`,用 `totalSquaresFor(variantId)` 决定位图大小 (xiangqi=256, international=64); EOFException 回落 xiangqi (旧客户端兼容)。

#### GUI 占位

- `client/CChessBoardScreen` 新增字段 `String variantId = "xiangqi"`,构造器 + `applySync` 接收 variantId; 在 `render` 里若 `"international".equals(variantId)` 走 `drawInternationalPlaceholder(gfx)` (显示「国际象棋 GUI 留待 v0.3.2」占位),否则继续 xiangqi 渲染。

### 测试

- `engine/xiangqi/XiangqiVariantTest`: 9 条用例, ID 稳定、9×10=90、INIT FEN round-trip、`Position implements BoardState`、`canMove` 通过合法车/红车不能斜走、`applyMove` 翻 sdPlayer、`isInCheck` 不污染 caller state、`firstLegalMoveReturnsAtLeastOne`、`searchBestMoveReturnsLegalMove`、`xiangqiHasNoStalemate`。
- `engine/international/InternationalChessVariantTest`: 11 条用例, ID/尺寸、INIT_FEN round-trip、初始 16/16 pieces、`malformedFenReturnsNull` (7 个错 FEN,均返 null 不抛)、`initialNotInCheck`、白方 O-O + castling rights 清除、吃过路兵、白兵 a7→a8 升变后 FEN 头 `Q3k3/8/...`、Scholar's Mate (Qxf7# 后 `isCheckmate` true)、50 步和棋 (`halfmoveClock>=100` → `isStalemate` true)、`firstLegalMove` 在合法局面返合法 Move 在 mate 局面返 `Move.NONE`。
- `network/LegalDestsBitmapTest`: 6 条用例, totalSquares-aware write/read round-trip + 边界 (0, 1, 255, 256, 全 true, 全 false, 跨字节位)+ 拒绝 null。
- 测试总数 60 → **79**,**全绿**。

### 已知限制

- 国际象棋 GUI 仅占位提示;真实棋盘 + 棋子 + 升变选择器 / 走法预览留待 v0.3.2。
- 国际象棋引擎无 alpha-beta (搜索只是 firstLegalMove wrapper),v0.3.2 引入。
- 协议 variantId 字段加在 buf 末尾;旧 v0.6 客户端读 `readUtf()` 会 EOFException,需要捕捉并回落 xiangqi。
- 服务端没有实机验证（容器无 LWJGL Display + 无 EULA TTY）—— 国际象棋 / 占位 GUI 真实表现以实机为准。

---

## [0.2.1] — v0.2 体验版收尾

目标：把 v0.2 路线上还没落地的最后三项（最近一步高亮、走子音效、`[qisheng]` admin 反馈 i18n）补上。

### 变更

#### 最近一步高亮

- `pvp/GameSession` 新增 `lastMoveSrc` / `lastMoveDst` 字段 + NBT 键 `LastSrc` / `LastDst`，在 `save()` / `fromTag()` 持久化（与 `SelectPoint` 同一层契约：越界回落 `-1`）。
- `pvp/GameLogic.tryMove` 在 `applyMove` 末尾 `session.setLastMoveSource(src) / setLastMoveDest(dst)`，PVC 与 PVP 路径都覆盖。
- `pvp/GameBroadcaster.broadcastSync` 与 `sendOpenScreen` 在合法落点位图之后追加 `short lastSrc + short lastDst`（4 字节）。`network/ChessSyncS2CPacket` + `ChessOpenScreenS2CPacket` 顺序读 4 字节。
- `client/CChessBoardScreen` 新增 `applySync(..., int lastMoveSrc, int lastMoveDest)` 重载 + 构造器重载，`drawLastMoveOverlay(gfx)` 在 pieces / selection / legal-dots 之前画半透明黄底色块 (`0xC0FFEB6B`)。
- 端到端合约：`CHESS_SYNC` / `CHESS_OPEN_SCREEN` 包末固定追加 `lastSrc` / `lastDst` 4 字节；服务端 `PvcController` 走 `applyMove` → `setLastMove*` 同条路径，所以人机走子也会高亮。

#### 走子音效

- `client/CChessBoardScreen.playMoveSound()` 在 `applySync` 检测 `lastSrc` / `lastDst` 变化时播放 `SoundEvents.NOTE_BLOCK_PLING`（轻量、不会和原版方块放置音效冲突）。
- 播放走 `Minecraft.execute()`，因为 `applySync` 可能由网络线程触发（`CHESS_SYNC` 接收回调）。
- 用 `(prevSrc, prevDst) ≠ (newSrc, newDst)` 作为「刚发生新走子」的判定 —— 不会因为重发同一帧 SYN 或重建棋盘（`CHESS_OPEN_SCREEN`）而重复响铃。

#### `[qisheng]` admin 反馈 i18n 收尾

- `command/ModCommands.doPurge` / `doMove OK` / `doSelect OK` 三处 `Component.literal("[qisheng] ...") → Component.translatable(...)`，英文玩家不再看到中文运维输出。
- `lang/zh_cn.json` 与 `lang/en_us.json` 各加 3 键：`cmd.purge.done` / `cmd.move.ok` / `cmd.select.ok` → **122/122 完全对称**。
- 余下 `Component.literal` 全部承载机器值（`session.getState().name()`、`BoardKey.describe()`、枚举名、ASCII 棋盘），不是用户文案，保留。

### 测试

- `GameSessionPersistenceTest` 加两条用例：
  - `lastMoveRoundTrip`：FEN 中途写入 `51` → `52`，save → fromTag 后保持；越界值（9999 / -2）回落 `-1`；全新会话默认 `-1`。
  - `roundTripIsStableAcrossGenerations`：把 `LastSrc` / `LastDst` 加入 `assertTagsEqual`，确保未来若字段增减漏写会立刻红。
- 测试总数 57 → **60**（含新增的 2 + 1 调优断言），**全绿**。

### 已知限制

- 服务端没有实机验证（容器无 LWJGL Display + 无 EULA TTY）—— 这是本仓库一直以来的限制，音效与高亮真实表现以实机为准。

---

## [0.2.0] — v0.2 体验版（最近一步可旋转）

- 棋盘 `BlockState.facing` 默认 `NORTH`，GUI 跟随 `viewerIsBlack XOR flipped` 翻转（参见 git `bef2909`）。
- 棋盘可旋转（`9dcca1c`）：服务端写入 1 字节 `flipped`，客户端 `boardFlipped = viewerIsBlack ^ flipped`，朝南棋盘整体翻 180°。
- i18n 119/119 键对称（`pvc.started` / `cmd.mode.*` / `server.*` 等）。
- GUI 细节打包：PopupOverlay / ActionPopup 实例化、ChatBoxWidget MAX_INPUT_CHARS=200 + scissor、SpectatorListWidget scissor、PlayerAvatarCache LRU、TextSanitizer。

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
