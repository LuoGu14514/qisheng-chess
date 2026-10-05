# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
详细的架构分析与后续规划见 [REVIEW-AND-PLAN.md](REVIEW-AND-PLAN.md)。

---

## [0.4.5] — 棋盘纹理改为 RGBA + alpha=255（修方块透明 bug）

实测 v0.4.4 后报怨：方块放置到地上时**透明**（看得到背景），但物品栏里的物品材质正常渲染，右击也能正常打开（说明服务端 block + session 都正常，只是客户端渲染问题）。

### 根因

v0.4.4 生成的 6 张棋盘 PNG（3 张 block top + 3 个 item）都是 **RGB 模式**（3 字节/像素，无 alpha 通道）。对比 cchess 的 `qisheng_cchess_top.png` 是 **RGBA 模式**（4 字节/像素）。`qisheng_gomoku_top.png` 等用 RGB 模式后，渲染管线在采样时按 alpha=0 显示 → 方块看不见。

通过 `Image.open(...).info` 检查 4 张 PNG 都没 `tRNS` / `sRGB` / `gamma` chunks → 排除 PNG 元数据问题。问题的就是 mode 字段本身。

### 修复

- `scripts/generate_board_textures.py`：
  - `Image.new("RGB", ...)` → `Image.new("RGBA", ...)`，所有像素写为 `(*rgb, 255)` 显式 alpha=255。
  - 调色板更鲜艳（gomoku bg 212→220，更暖；roster/象棋 bg 180→180 但 grid 20→15，对比度提升）。
  - grid 线 1px → **2px 厚**（mipmap 缩到 16×16 后仍看得清）。
  - star 点 3×3 → **5×5**（远处能数出来）。
  - **删 wood-grain noise**：v0.4.4 用 ~20k 个随机散点模拟木纹，视觉噪声没用反而把 PNG 压缩率搞坏。
- 6 张 PNG（3 block + 3 item）全部从 RGB 升 RGBA + alpha=255。

### 测试

没有渲染/逻辑改动 → 没加测试。128 tests 全部继续 PASS。

### 构建

- `:common:compileJava :common:test :fabric:remapJar` → BUILD SUCCESSFUL，128 tests PASSED。
- jar = `qisheng_chess-fabric-0.4.5.jar` **255457 B**（vs 0.4.4 = 387589 B，**-132 KB**——不是问题，是 PNG 压缩率显著改善：简化图案 + 删单一 noise 后 zlib 工作得更好）。
- 部署：删除 `mods/qisheng_chess-fabric-0.4.4.jar`，复制 0.4.5 到 `D:\PCL 正式版 2.12.6\89\.minecraft\versions\1.20.1-Fabric 0.19.5\mods\`。

---

## [0.4.4] — 棋盘缩放变种感知 + 方块所有面显示棋盘纹理

实测 v0.4.3 后两个体验问题：

1. **围棋 / 五子棋棋盘太大**：原 `recomputeLayout()` 只对 `cols >= 15` 棋盘 cap 到 40px cell，其余（包括 9×9 go9、9×10 xiangqi、8×8 国际）走 `CELL_MAX = 80`。在中等分辨率窗口里 go9 cell 实测跑到 80px → 8 格就是 640px，整个棋盘占了窗口一大半。
2. **方块放置后看不到棋盘纹理**：v0.4.3 的 cube model 只把 board 纹理放在 `up` 面，其余 5 面都用 `oak_planks`。玩家从水平角度看方块时只看得到原木板，跟"物品栏里能看到棋盘图案"形成强对比，第一眼会觉得"纹理没渲染出来"（实际只渲染到了顶部）。

### 变更

#### 棋盘缩放变种感知（`CChessBoardScreen.java`）

- `CELL_MAX` 从 80 → **56**，`CELL_MIN` 从 32 → **20**。
- `recomputeLayout()` 的 cap 由单一阈值改为按 `variantCols` 分档：
  - `>= 19`（go19）：24
  - `>= 15`（gomoku）：32
  - `>= 9`（xiangqi、go9）：48
  - 其余（国际 8×8）：56

实测各棋盘最大渲染尺寸（1920×1080 窗口，左 170 + 右 210 + 上 28 + 下 24）：

| 棋盘 | cell | board |
|---|---|---|
| go19 (19×19) | 24 | 432 |
| gomoku (15×15) | 32 | 448 |
| go9 (9×9) | 48 | 384 |
| xiangqi (9×10) | 48 | 384 |
| 国际 (8×8) | 56 | 392 |

棋盘最大边从 720px（go19 @ cell=40）降到 432px，go9 从 640px 降到 384px。所有棋盘最大占屏宽 432px ≈ 22.5%（1920px 屏），给左侧 badge + 右侧按钮面板留足空间。

#### 方块所有面显示棋盘纹理（`models/block/qisheng_{gomoku,go9,go19}.json`）

把 `north` / `south` / `east` / `west` / `particle` 从 `oak_planks` 全部改成各自的 `_top.png`。`down` 仍保持 `oak_planks`（避免从天上看方块底部的尴尬对称问题）。

副作用：现在放置方块后从任意水平角度都能看到棋盘纹理（旋转 90° 仍然能识别棋种），不再像 v0.4.3 那样需要玩家飞到方块正上方才能看到图案。

cchess 方块没改（保持原样，用户没抱怨）。

### 测试

没有逻辑改动 → 没加测试。128 tests 全部继续 PASS。

### 构建

- `:common:compileJava :common:test :fabric:remapJar` → BUILD SUCCESSFUL，128 tests PASSED。
- jar = `qisheng_chess-fabric-0.4.4.jar` 387589 B（vs 0.4.3 = 387548 B，+41 B = JSON 模型 6 个面各加 `qisheng_chess:block/qisheng_<name>` 引用 + gradle.properties 版本字符串）。

---

## [0.4.3] — 补齐 v0.4.2 资源 + 修 onPlace 抢跑导致象棋 fallback 失效

目标：用户实测 v0.4.2 反馈三个问题——创造物品栏只有象棋、其他棋盘没材质、`/give` 拿到 gomoku/go9/go19 后右键打开仍是象棋。三个问题全部由 v0.4 的资源遗漏 + v0.4.2 的会话初始化路径 bug 导致。

### 变更

#### 资源补齐

- **新增 3 个 blockstate**：`blockstates/gomoku.json` / `go9.json` / `go19.json`，每条 `"" → qisheng_chess:block/qisheng_<name>` 单 variant。
- **新增 3 个 block model**：`models/block/qisheng_gomoku.json` / `qisheng_go9.json` / `qisheng_go19.json`，每个走 `minecraft:block/cube` 父模型 + 顶面 `_top.png` + 6 面复用 `oak_planks`（跟 cchess 一致，玩家靠顶面纹理区分棋种）。
- **新增 3 个 item model**：`models/item/gomoku.json` / `go9.json` / `go19.json`，走 `item/generated` 父模型 + `layer0`。
- **新增 6 张程序生成纹理**（`scripts/generate_board_textures.py` + Pillow）：
  - `textures/block/qisheng_gomoku_top.png` (256×256)：暖色木纹 + 15×15 网格 + 5 星。
  - `textures/block/qisheng_go9_top.png` (256×256)：深色木纹 + 9×9 网格 + 4 星。
  - `textures/block/qisheng_go19_top.png` (256×256)：深色木纹 + 19×19 网格 + 9 星（标准 9 星布局）。
  - `textures/item/gomoku.png` / `go9.png` / `go19.png` (16×16)：简化版顶视图——边框 + 网格 + 中心一颗星。

#### 创造 tab 补齐

- `ModCreativeTabs.populateTabs()` 之前只 append 了 `CCHESS` 一个 BlockItem，导致 `GOMOKU` / `GO9` / `GO19` 即便注册了 BlockItem 也进不了创造物品栏。补三个 `appendStack` 调用。

#### 关键 bugfix：`CChessTileEntity.ensureSession` 抢跑

- **根因**：`AbstractChessBoardBlock.onPlace` 调用 `SessionManager.getOrCreate(pos)` 创建会话，但**不带 variant ID**——结果新建会话的 variant = `DEFAULT_ID` ("xiangqi")。随后用户右键 → `CChessTileEntity.ensureSession(preferredVariantId="gomoku")` 被调用，但此时 live session 已经存在，函数第一段 `if (live != null) return live` 直接 return，**完全无视 preferredVariantId**。结果：一个围棋方块绑了象棋会话。
- **修复**：在 `live != null` 分支也补 preferredVariantId 应用——如果 live variant 仍是 `DEFAULT_ID`，`setVariantId(preferredVariantId)` 覆盖它。restored session 优先级不变（restored > preferred，存档里存的 variant 不被方块类型覆盖）。
- **为什么不改 onPlace**：`onPlace` 里的 `getOrCreate` 删掉会破坏"服务器一重启, 棋盘就废了"的旧 case（CChessTileEntity.java:31-33 的注释解释）。在 ensureSession 修覆盖两种情况——onPlace 抢跑创建 + onPlace 没创建——更稳。

### 测试

没有新功能，没有新变种/AI/规则逻辑改动 → 没加测试。128 tests 全部继续 PASS。

### 文件表

| 文件 | 说明 |
|---|---|
| `common/.../resources/.../blockstates/gomoku.json` + `go9.json` + `go19.json` | 新增。每个 `"" → qisheng_chess:block/qisheng_<name>`。 |
| `common/.../resources/.../models/block/qisheng_gomoku.json` + `qisheng_go9.json` + `qisheng_go19.json` | 新增。`parent: minecraft:block/cube`，up 用各自 _top.png，其余 6 面 oak_planks。 |
| `common/.../resources/.../models/item/gomoku.json` + `go9.json` + `go19.json` | 新增。`parent: item/generated`，layer0 用各自 item PNG。 |
| `common/.../resources/.../textures/block/qisheng_gomoku_top.png` + `qisheng_go9_top.png` + `qisheng_go19_top.png` | 新增。256×256。 |
| `common/.../resources/.../textures/item/gomoku.png` + `go9.png` + `go19.png` | 新增。16×16。 |
| `scripts/generate_board_textures.py` | 新增。Pillow 程序生成器。 |
| `common/.../item/ModCreativeTabs.java` | populateTabs append 三个新 BlockItem。 |
| `common/.../tileentity/CChessTileEntity.java` | ensureSession 在 live 分支也应用 preferredVariantId。 |
| `gradle.properties` | `mod_version=0.4.2 → 0.4.3`。 |

### 构建

- `:common:compileJava :common:test :fabric:remapJar` → BUILD SUCCESSFUL，128 tests PASSED。
- jar = `qisheng_chess-fabric-0.4.3.jar` 387548 B（vs 0.4.2 = 246637 B，+140911 B = 程序生成的 6 张 PNG 纹理）。

---

## [0.4.2] — 围棋拆成两个独立方块

目标：回应 v0.4.1 之后的用户反馈（"围棋提供两个棋盘"）—— 把 v0.4 引入的单方块 + `BOARD_SIZE` BlockState 改成两个独立方块，物品栏里能直接看到 9 路 / 19 路两个 BlockItem。

### 变更

#### 方块拆分

- **新增** `block/GoBoard9Block`：`extends AbstractChessBoardBlock`，`VARIANT_ID = "go9"`，用 `goProperties()`，无 `BOARD_SIZE` property —— 方块就是 9 路。
- **新增** `block/GoBoard19Block`：同上，`VARIANT_ID = "go19"`。
- **删除** `block/GoBoardBlock`：旧版用 `IntegerProperty BOARD_SIZE.create("size", 9, 19)` + `getVariantId(BlockState)` 路由到 `GO_9 / GO_19` 的方案被两个独立具体类取代。
- **重构** `block/AbstractChessBoardBlock` 的 Javadoc：`GoBoardBlock` 引用替换为 `GoBoard9Block, GoBoard19Block`。

#### 注册

- `block/ModBlocks`：从 `CCHESS + GOMOKU + GO` 三个改为 `CCHESS + GOMOKU + GO9 + GO19` 四个。
- `item/ModItems`：从只有 `CCHESS` 一个 BlockItem 改为 `CCHESS + GOMOKU + GO9 + GO19` 四个（v0.4.1 已经把 GOMOKU/GO 补上了，现在再分一下）。
- `tileentity/ModBlockEntities`：单一 `BlockEntityType` 绑定四个 Block；`CChessTileEntity.ensureSession(String)` 仍负责把 variantId 喂给 tile entity，所以棋种信息不依赖 BlockEntityType 区分。

#### i18n

- `lang/zh_cn.json` + `lang/en_us.json`：把 `block.qisheng_chess.go` / `item.qisheng_chess.go` 替换为 `block.qisheng_chess.go9` + `block.qisheng_chess.go19`（同样 item. 前缀）。中文 "围棋棋盘" / 英文 "Go Board" 拆成 "围棋棋盘 (9 路)" / "Go Board (9x9)" + "围棋棋盘 (19 路)" / "Go Board (19x19)"。

#### 设计决策

- 为什么拆方块而不是用 `IntegerProperty`？两个原因：
  1. 创造模式物品栏里玩家直接看到 "Go Board (9x9)" / "Go Board (19x19)" 两件 BlockItem，比 `/setblock ... [size=19]` 直观得多。
  2. `BoardVariant` 接口的 `getVariantId()` 是无参的 `String` —— 之前的 `GoBoardBlock` 必须 override `getVariantId(BlockState)` 才能把尺寸传进去，导致 `AbstractChessBoardBlock.use()` 里 `ensureSession(getVariantId(state))` 多走一条间接路径。拆成两个具体类后无参 `getVariantId()` 直接生效。
- `AbstractChessBoardBlock` 的 `getVariantId()` 现在全部是无参方法；`getVariantId(BlockState)` 默认实现保留（直接调无参版），三个具体子类不再 override。

#### 已知限制

- 已经下好的围棋存档不会因为方块拆分而错位 —— `GameSession` 持久化只存 `variantId`（"go9" 或 "go19"）和 FEN，不存方块类型。所以把 9 路棋盘拆掉、放 19 路方块在同一个坐标，对局数据不会自动迁移，但 tile entity 的 NBT 也是按 variantId 走，不会冲突。
- 五子棋 / 围棋方块的 break-the-board 行为不变：右击仍然 open GUI + 加入 session，方块类型决定初始 variantId。

#### 文件表

| 文件 | 说明 |
|---|---|
| `common/.../block/GoBoard9Block.java` | 新增。围棋 9 路方块，`VARIANT_ID = "go9"`。 |
| `common/.../block/GoBoard19Block.java` | 新增。围棋 19 路方块，`VARIANT_ID = "go19"`。 |
| `common/.../block/GoBoardBlock.java` | 删除。被两个独立具体类取代。 |
| `common/.../block/ModBlocks.java` | 注册 CCHESS + GOMOKU + GO9 + GO19 四个方块。 |
| `common/.../block/AbstractChessBoardBlock.java` | Javadoc 更新（GoBoardBlock → GoBoard9Block + GoBoard19Block）。 |
| `common/.../item/ModItems.java` | 四个 BlockItem 全部注册。 |
| `common/.../tileentity/ModBlockEntities.java` | 单一 BlockEntityType 绑定四个 Block。 |
| `common/.../lang/zh_cn.json` + `en_us.json` | go → go9 + go19。 |
| `gradle.properties` | `mod_version = 0.4.2`。 |
| `README.md` | 方块清单更新 + 版本号。 |

#### 构建

- `:common:compileJava :common:test` → BUILD SUCCESSFUL，128 tests PASSED。
- `:fabric:remapJar` → BUILD SUCCESSFUL，jar = `qisheng_chess-fabric-0.4.2.jar` 246637 B。

---

## [0.4.1] — AI 优化 + GUI 补全 + 国际象棋规则补齐

目标：落实 v0.4 之后的代码审查结论（用户优先级 = GUI 优化 + PVC AI 优化 + 基础功能补齐；不做锦标赛等复杂功能）。重点：国际象棋 GUI 从占位变为真实渲染、围棋 Pass 按钮、三连重复 + 子力不足和棋、per-variant AI 强化、i18n 收尾。

### 变更

#### GUI 补全

- **国际象棋完整 8×8 渲染**：新增 `drawInternationalBoard(gfx, bs)` 与 `drawInternationalPiece(gfx, cx, cy, r, pc, v, sq, bs)`；浅盘白子 + 深字、黑盘白字；a-h / 1-8 坐标标签；字母从 `v.pieceFenChar(bs, sq)` 读取。`render()` 把 `drawInternationalPlaceholder(gfx)` 换成 `drawInternationalBoard(gfx, bs)`。新增常量 `COL_INT_LIGHT=0xFFEFE2C8` / `COL_INT_DARK=0xFF8E6A4A`。
- **title bar 变种名**：`qisheng.chess.screen.title_bar` 从 3 槽升级为 4 槽（变种 + 模式 + 角色 + 状态）；`CChessBoardScreen` 渲染标题时新增 `Component.translatable(variant().displayNameKey()).getString()`。
- **lastMove 校验变种感知**：`CChessBoardScreen` 内 4 处把 `ChineseChessEngine.isSquare(...)` 改为 `variant().isValidSquare(...) + v.fileOf/rankOf`（line 262-263、312-313、775、1146）。覆盖范围从仅象棋扩展到国际 / 五子棋 / 围棋，last-move 高亮 + 走子音效在所有变种下都会触发。
- **drawLegalDots 变种感知重载**：新增 `drawLegalDots(GuiGraphics, BoardState, int)` 用 `v.indexForFileRank + v.pieceAt`；原 `drawLegalDots(GuiGraphics, Position, int)` 内部也改走 variant 路径。
- **去重 select 分支**：`mouseClicked` 里原来重复 arm 两次 `sendInteract(ACTION_SELECT, ...)`，现在只发一次。
- **围棋 Pass 按钮**：新增 `ACTION_PASS = 3` 私有常量 + `onClickPass()` + 仅当 `isGo()` 时显示的按钮（`maxButtons` 7 → 8）；服务端走 `ChessInteractC2SPacket.handlePass` → `GameLogic.tryPass` → `GoVariant.canMove(-1,-1)` → `GoVariant.applyMove(-1,-1)`。

#### 国际象棋规则补齐

- **三次重复和棋**：`IntChessBoard` 新增 `long positionKey` + `ArrayList<Long> positionHistory`，`makeMove` 末尾追加新 key；`InternationalChessVariant.positionKey(b)` 是 Zobrist-free 指纹（h *= 31 + 每字节 + sdPlayer + castling + enPassantSq+1）；`isStalemate` 在 `positionHistory.size() >= 3` 时检查当前 key 是否出现 ≥3 次。
- **子力不足和棋**：`InternationalChessVariant.insufficientMaterial(b)`：K vs K / K + 任一 minor vs K / K+B vs K+B 同色格象 = draw；其他情况不 draw。判定算法：白/黑 piece count + bishop colour square 分类（light = (file + rank) even）。

#### PVC AI 强化

- **五子棋 1-ply 攻防 AI**：`GomokuVariant.searchBestMove` 走 `placementPriority` 优先级：WIN_OWN=100000 / WIN_BLOCK=90000 / OPEN4_OWN=8000 / OPEN4_BLOCK=7000 / CLOSED4_OWN=500 / OPEN3_OWN=400 / CLOSED3_OWN=50 / OPEN2_OWN=10。新增 `patternScore(b, sq, stone)` 4 方向 H/V/diag1/diag2 综合评分。
- **国际象棋 1-ply MVV-LVA AI**：`InternationalChessVariant.searchBestMove` 走 `moveScore(b, src, dst)` 评分（MVV-LVA = victim value - attacker value/10 + 中央偏好 6-distance）。新增 `PIECE_VALUES = {0, 1, 3, 3, 5, 9, 0}`。
- **围棋 1-ply capture-or-extend AI**：`GoVariant.searchBestMove` 走 `placementPriority` 分层：captures×100 + ownAdjacency×30 + oppAdjacency×20 + centre×0 - ownEye×50。新增 `countGroupLiberties` + `groupSize` flood-fill helpers。
- **per-variant 时间预算**：`PvcController` 新增 `thinkMillisFor(variant)` (xiangqi=600ms, 其他=80ms) + `replyDelayFor(variant)` (xiangqi=400ms, 其他=120ms)；`searchThenPlay` 改调 `thinkMillisFor(variant)`，sleep 用 `replyDelay - elapsedMillis`。
- **围棋 pass sentinel**：Go 变种用 `Move(-1, -1)` 表示 pass；`GoVariant.canMove` 优先检查 `src==-1 && dst==-1`，`applyMove` 路由到 `applyPass(state)`；其他变种 `canMove` 自然 reject。

#### i18n 收尾

- **BoardMessages.java**：把 `"红方/黑方"` / `"轮到"` / `"你走子"` / `"旁观"` / `"等待"` 全部换成 `Component.translatable`。
- **ChessResignC2SPacket.java**：把 `" 认输 — 红方胜/黑方胜"` 字面量换成 `Component.translatable("qisheng.chess.resign.winner_announce", playerName, winner)`。
- **新增 lang keys**（zh_cn + en_us 各 6 对）：
  - `qisheng.chess.turn.label` = "轮到 %s" / "Turn: %s"
  - `qisheng.chess.turn.your_move` = "← 你走子" / "← your move"
  - `qisheng.chess.turn.waiting` = "(等待;%s走子)" / "(waiting; %s to move)"
  - `qisheng.chess.turn.spectating` = "(旁观;%s走子)" / "(spectating; %s to move)"
  - `qisheng.chess.resign.winner_announce` = "%s 认输 — %s" / "%s resigned — %s"
  - `qisheng.chess.game.over.{red_win,black_win}` 复用既有 "红方胜!"/"黑方胜!"。

### 测试

- `engine/gomoku/GomokuVariantTest` 19 → 20：删除 `searchBestMoveIsFirstLegal`，改 `searchBestMoveOnEmptyBoard(assertEquals 0)` + 新增 `aiTakesWinningFour`(4 子开四,AI 必下中间完成五连)。
- `engine/go/GoVariantTest` 21 → 22：删除 `searchBestMoveIsFirstLegal`，改 `searchBestMovePrefersCentreOnEmptyBoard(assertEquals 40)` + 新增 `aiTakesObviousCapture`(白子被打,AI 必提子)。
- `engine/international/InternationalChessVariantTest` 13 → 18：新增 `threefoldRepetitionIsDraw`(Ng1-f3 × 16 步循环) + `insufficientMaterialBareKings` / `insufficientMaterialKnightOrBishopVsKing` / `insufficientMaterialSameColourBishops`(a1 + b8 同色格) + `insufficientMaterialNotOppositeColourBishops`(a1 + h7 异色格,不是 draw) + `aiPrefersCaptureOverQuietMove`(黑 N 必须 Nxd5 而不是 Nb5)。
- 全部 **128 tests passed, 0 failures**（ChineseChessEngineTest 8 + GomokuVariantTest 20 + GoVariantTest 22 + InternationalChessVariantTest 18 + XiangqiVariantTest 10 + PositionTest 11 + SearchTimeBudgetTest 5 + LegalDestsBitmapTest 6 + GameSessionPersistenceTest 13 + PvcGameLoopTest 15）。

### 文档

- `docs/ai-engine-audit.md`: 保存并行代码审查（PVC AI 引擎）报告作为参考。

### 已知限制

- 国际象棋 AI 仍是 1-ply MVV-LVA，没有 alpha-beta；棋力只能挡住无脑送子。
- 五子棋 AI 是 1-ply 攻防模式；不读长链 / 不算双三 / 不算禁手（Renju 规则未启用）。
- 围棋 AI 只贪 capture + 临接 + 中心；不会读真眼 / 不会打劫判断。
- 服务端没有实机验证（容器无 LWJGL Display + 无 EULA TTY）。

---

## [0.4.0] — 五子棋 + 围棋 + 独立方块

目标：在 v0.3.1 引擎抽象之上引入两类新棋（五子棋 15×15、围棋 9 路 / 19 路），并为每个棋类提供独立方块。游戏逻辑完全 variant-aware；GUI 提供功能性的简化渲染（无 AI、无高亮、无坐标引导）。

### 变更

#### 新 BoardVariant 实现

- `engine/gomoku/GomokuBoard` (50 行): `byte[225]` 棋盘 (0=空, 1=黑, 2=白), `sdPlayer`, `moveCount`, `winner`。
- `engine/gomoku/GomokuVariant` (~280 行): 15×15, FEN dialect `<15 ranks> <turn>`, `canMove` 要求 `src==dst` + 空 + winner==0, `applyMove` 落子 + 翻转 + `detectWinner` (4 方向 H/V/diag1/diag2, 5+ 胜, Renju 接受 overline); `isCheckmate = winner!=0`, `isStalemate = board 满且 winner==0`; `searchBestMove = firstLegalMove` (v0.4 简化版, 真实 AI 待后续); `legalDestsBitmapSize = 29`。
- `engine/go/GoBoard` (64 行): `byte[size*size]` 棋盘, `sdPlayer`, `blackCaptures / whiteCaptures`, `koSquare = -1`, `passes`, `finished`。
- `engine/go/GoVariant` (~489 行): 静态实例 `GO_9` (id="go9", 9×9) + `GO_19` (id="go19", 19×19), KOMI=7.5, FEN dialect `<rows> <side> <passes> <koSq> <bCap> <wCap>`; 完整 placement + flood-fill capture + suicide 禁止 + 简单 ko square; `applyPass` 走 `Move(-1,-1)` sentinel; `isCheckmate = finished` (2 passes 后); `scoreDelta` 走中国数子规则。
- `BoardRegistry` 注册 5 个变体: xiangqi + international + gomoku + go9 + go19。

#### BoardVariant 接口扩展

- 新增 3 个 helper 方法 `indexForFileRank(file, rank)` / `fileOf(sq)` / `rankOf(sq)` — GUI 不再写 `if (variantId == "international")` 硬编码分支，全部走 variant 描述子。
- `XiangqiVariant.indexForFileRank = Position.COORD_XY(file+3, rank+3)`, `fileOf = (sq & 0xF) - 3`, `rankOf = ((sq>>4)&0xF) - 3`。
- `InternationalChessVariant.indexForFileRank = IntChessBoard.sq(file,rank)`, `fileOf = sq & 7`, `rankOf = sq >>> 3`。
- `GomokuVariant.indexForFileRank = file + rank*15`, `fileOf = sq % 15`, `rankOf = sq / 15`。
- `GoVariant` 按 size 计算 (`indexForFileRank = file + rank*size`)。

#### 抽象方块基类

- 新增 `block/AbstractChessBoardBlock`: 抽取 `use / onPlace / onRemove / FACING / rotate / mirror / newBlockEntity` 共享逻辑; abstract `getVariantId()` + `getVariantId(BlockState)` 默认调无参版; `resetFinishedBoard` 静态方法; 3 个静态属性 helper: `xiangqiProperties()` / `gomokuProperties()` / `goProperties()` (mapColor + strength + sound + noOcclusion)。
- `CChessBoardBlock` (15 行): `extends AbstractChessBoardBlock`, `VARIANT_ID = "xiangqi"`。
- `block/GomokuBoardBlock` (30 行): `extends AbstractChessBoardBlock`, `VARIANT_ID = "gomoku"`, 用 `gomokuProperties()`。
- `block/GoBoardBlock` (90 行): `extends AbstractChessBoardBlock`, `VARIANT_PREFIX = "go"`, `BOARD_SIZE = IntegerProperty.create("size", 9, 19)`, `getVariantId(BlockState)` 按 `state.getValue(BOARD_SIZE)` 路由到 `GO_9 / GO_19`。
- `ModBlocks`: 注册 `CCHESS + GOMOKU + GO` 三个 `DeferredRegister.Supplier`。
- `ModBlockEntities`: 单一 `BlockEntityType` 绑定 3 个 Block (`CCHESS + GOMOKU + GO`) 共享 `CChessTileEntity`。

#### GameSession 重构

- `private final Position chessData` → `private BoardState boardState` (variant-specific)。
- 构造器 `setVariantId(BoardRegistry.DEFAULT_ID)` 触发 `initialState()`。
- `setVariantId(id)` 在变种变化时重置 `boardState = variant.initialState()`。
- `getChessData()` 改为 `return boardState instanceof Position p ? p : null` — xiangqi 路径保留兼容, 其它变种返 null (server 端不需要)。
- `save()` 走 `variant.toFen(boardState)`; `fromTag()` 走 `variant.parseState(fen)` (null fallback)。
- `checkGameOver()` 走 `v.isCheckmate` + xiangqi 走 `isRepeat / reachMoveLimit` + international 走 `halfmoveClock>=100` + `v.isStalemate`。
- `CChessTileEntity.ensureSession(String preferredVariantId)` overload — 已有 live/restored 不覆盖; `getOrCreate` 出来的新会话若 `preferredVariantId != null && !empty && 仍是 DEFAULT_ID` 则 `setVariantId(preferredVariantId)`。

#### ModCommands

- `doSelect`: `session.getChessData().squares[sq]` → `session.getVariant().pieceAt(session.getBoardState(), sq)`。
- `doReset` / `doLeave`: `session.getChessData().fromFen(...)` → `session.setBoardState(session.getVariant().initialState())`。
- `doBoard`: xiangqi-only ASCII 输出加 fallback 到 `qisheng.chess.cmd.board.non_xiangqi` i18n。

#### GUI 改造 (variant-aware)

- `client/CChessBoardScreen`:
  - 新增 `variantId / cachedBoardState / cachedVariant` 字段; `boardState()` 走 `variant.parseState(fen)` 缓存。
  - `cols() / rows()` 从 `variant.boardFiles / boardRanks` 动态取。
  - `recomputeLayout()` 用动态 cols/rows + cell cap (≥15 列时降到 40)。
  - `viewFile / viewRank / fenFileFromView / fenRankFromView` 用动态 cols/rows 做翻转。
  - `legalDestinations()` 改 totalSquares-aware: 服务端位图按 `v.totalSquares()` 决定长度。
  - `render()` 分支: `xiangqi` 走原 drawGrid + drawRiver + drawPalace + drawPieces; `international` 仍走占位; `gomoku` 走 `drawGomokuBoard`; `go9 / go19` 走 `drawGoBoard`。
  - 新增 `drawGomokuBoard` (15×15 网格 + 黑/白圆子, 无坐标引导), `drawGoBoard` (网格 + star points 9×9 中心 / 19×19 9 点 + 黑/白圆子), `drawPlacementLastMoveOverlay` (五子棋/围棋最近一手高亮), `drawStarPoints` (hoshi)。
  - `mouseClicked` / `handleBoardClick` 变种路由: 五子棋/围棋 单击直接 `ACTION_MOVE(src==dst)`, 不走选子→落子两步。
  - `drawStone` 共享工具: 黑/白圆子 + 灰色描边, 五子棋/围棋 共用。

#### i18n

- 5 个变种显示名: `qisheng.chess.variant.{xiangqi,international,gomoku,go9,go19}` (zh_cn + en_us 各 5 键, 对称 133 键)。
- 3 个方块/物品名: `block.qisheng_chess.{cchess,gomoku,go}` + `item.qisheng_chess.{cchess,gomoku,go}`。

### 测试

- `engine/gomoku/GomokuVariantTest` (19 条): id / sizes / fenRoundTripInitial / blackPlaysFirst / placementBasics / canMoveRejectsSrcNeqDst / blackWinsHorizontal / whiteWinsVertical / diagonalWin / firstLegalMovePicksFirstEmpty / searchBestMoveIsFirstLegal / noCheckConcept / pieceAndSideAccessors / parseStateRejectsJunk / sdPlayerFlipsCorrectly / pieceFenCharAfterWin / fenRoundTripAfterMove / moveEncodingSafe / stalemateFalseOnEmpty。
- `engine/go/GoVariantTest` (21 条): idsAndSizes / fenRoundTripInitial / fenRoundTrip19 / blackPlaysFirst / basicPlacementAndSdFlip / suicideForbidden / captureTakesOneStone / twoPassesEndsGame / moveResetsPasses / finishedBoardRejectsMoves / fenRoundTripPreservesState / fenRoundTripPreservesKoSquare / parseStateRejectsJunk / simpleKoGuard / firstLegalMoveEmpty / searchBestMoveIsFirstLegal / pieceAndSideAccessors / differentSizesUseSameApi / pieceFenCharMapping / noCheckConcept / scoreDeltaBasic。
- 全部 **119 tests passed, 0 failures, 0.954s** (ChineseChessEngineTest 8 + GomokuVariantTest 19 + GoVariantTest 21 + InternationalChessVariantTest 11 + XiangqiVariantTest 10 + PositionTest 11 + SearchTimeBudgetTest 5 + LegalDestsBitmapTest 6 + GameSessionPersistenceTest 13 + PvcGameLoopTest 15)。

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
