# Qisheng Chess Audit Report — Game Flow + GUI + Resources

Audit completed on the working tree at `D:\代码\qisheng-chess` (read-only). Scope: per-variant PVP/PVC playthrough, GUI rendering correctness, resource presence, I18n parity. Findings below reference exact files and lines.

## 1. Per-variant scorecard

| Variant   | PVP | PVC | GUI | Resources | I18n |
|-----------|-----|-----|-----|-----------|------|
| xiangqi   | PASS | PASS | PASS | PASS | PASS |
| international | PASS | PASS | PASS | PASS | PASS |
| gomoku    | PASS | PASS | PASS | PASS | PASS |
| go9       | PASS | PASS | PASS | PASS | PASS |
| go19      | PASS | PASS | PASS | PASS | PASS |

### 1.1 xiangqi

- **PVP flow**: `AbstractChessBoardBlock.use()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\block\AbstractChessBoardBlock.java:165-269` — proximity check (≤5 blocks `AbstractChessBoardBlock.java:172`), join as red on empty board `SessionManager.joinGame()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\pvp\SessionManager.java:165-193`, second player flips state to PLAYING.
- **PVC flow**: same `use()` -> first join becomes red, black stays null -> `PvcController.maybeSchedule()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\pvp\PvcController.java:125`.
- **Game-over**: `GameSession.checkGameOver()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\pvp\GameSession.java:226-272` — xiangqi arm at `:262-265` calls `CChessUtil.isRepeat()` (3-fold, `CChessUtil.java:111-113`) and `CChessUtil.reachMoveLimit()` (>60 moves, `CChessUtil.java:106-108`). Checkmate via `XiangqiVariant.isCheckmate()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\engine\xiangqi\XiangqiVariant.java:129-132` (xqwlight `isMate()`).
- **GUI**: variant-aware render dispatch `CChessBoardScreen.java:778-805`; xiangqi gets frame+grid+river+palace+lastMoveOverlay+pieces+selection+legalDots (`CChessBoardScreen.java:1114-1176`, `:1236-1244`, `:1254-1274`, `:1292-1313`).

### 1.2 international

- **PVP / PVC flow**: same `use()` machinery; engine work in `InternationalChessVariant` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\engine\international\InternationalChessVariant.java`.
- **Game-over**: arm at `GameSession.java:266-268` (halfmoveClock ≥ 100) + `isCheckmate` (king-in-check + no legal moves, `InternationalChessVariant.java:245-250`) + `isStalemate` which covers halfmove, threefold (positionHistory ≥ 3 with same key, `InternationalChessVariant.java:252-270`), and insufficient material (FIDE Article 5.2.2, `InternationalChessVariant.java:276-309`).
- **GUI**: 8x8 alternating squares drawn directly in `drawInternationalBoard()` `CChessBoardScreen.java:852-912`; render dispatch at `:796-798`.

### 1.3 gomoku

- **PVP / PVC flow**: same `use()` machinery.
- **Game-over**: `GomokuVariant.isCheckmate` returns `winner != EMPTY` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\engine\gomoku\GomokuVariant.java:293-296`. `winner` set by `detectWinner()` `GomokuVariant.java:326-356` after each `applyMove()` (`:146-154`) which scans for ≥5-in-row. **GameSession dispatches on `GomokuVariant && GomokuBoard` to read the `winner` field directly** `GameSession.java:236-239` (not via sdPlayer-flip) — correct: a 5-in-row move flips sdPlayer to the LOSING side, so the naive sdPlayer trick would mis-declare the victor.
- **Stalemate (draw)**: `isStalemate` returns `moveCount >= totalSquares()` (full board no winner) `GomokuVariant.java:298-302`.
- **GUI**: 15x15 grid `drawGomokuBoard()` `CChessBoardScreen.java:939-975`; stones by `GomokuBoard.BLACK` check `:971`.

### 1.4 go9 / go19

- **PVP / PVC flow**: same `use()` machinery.
- **Game-over**: `GoVariant.isCheckmate` returns `b.finished` (`D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\engine\go\GoVariant.java:436-442`); `finished` set by `applyPass()` after 2 consecutive passes (`:280-291`). Winner via `scoreDelta()` (Chinese area + KOMI 7.5, `:573-612`). `GameSession.java:240-248` reads `scoreDelta` to choose BLACK_WIN/RED_WIN/tie → BLACK.
- **GUI**: `drawGoBoard()` `CChessBoardScreen.java:982-1018`; star points `drawStarPoints()` `:1024-1040` — 9x9 → 1 center hoshi (4,4); 19x19 → 9 hoshi at (3,3)(3,9)(3,15)(9,3)(9,9)(9,15)(15,3)(15,9)(15,15).
- **Pass button**: shown only for Go `CChessBoardScreen.java:557-559`; `tryPass()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\pvp\GameLogic.java:151-170` uses Move(-1,-1) sentinel routed to `GoVariant.applyPass()`.

## 2. Engine-move timing (PVC)

`GameLogic.applyMove()` `GameLogic.java:173-218`: canMove → applyMove → applyXiangqiIrrev (xiangqi only) → flip `session.sdPlayer` (`:202`) → set selectPoint, lastMove src/dst → `checkGameOver()` (`:212`). After this, the caller (PVC) does `broadcastSync()` `GameBroadcaster.broadcastSync()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\pvp\GameBroadcaster.java:84-126`, which calls `PvcController.maybeSchedule()` at `:91`. `maybeSchedule()` `PvcController.java:125-157` re-checks `isComputerToMove()` — the new sdPlayer has the empty seat, so the next iteration schedules the AI for the new side.

**IN_FLIGHT invariant**: `IN_FLIGHT.remove(key)` is called at the top of the tick-thread `play()` method `PvcController.java:205`, before any throwable code. The catch at `:260-263` also removes on throw. Both paths run only on failure of their enclosing scope; the normal path has a single remove at `:205`. No double-remove bug. The 30s timeout `PvcController.java:98` is the safety net for a missing removal (e.g., server crash mid-search).

## 3. GUI rendering verification (per variant)

All five variants render via the same screen `CChessBoardScreen.java` with variant-aware dispatch `CChessBoardScreen.java:778-805`. Frame `drawFrame()` `:1105-1112`, title bar (4-slot translatable, `qisheng.chess.screen.title_bar`) `drawTitle()` `:1341-1361`, status `drawStatus()` `:1363-1385`, location hint `drawHint()` `:1387-1393`.

| Feature | Code location | All variants? |
|---------|---------------|---------------|
| Title bar with variant name | `drawTitle` `CChessBoardScreen.java:1341-1361` (`variant().displayNameKey()`) | yes |
| Last-move overlay | `drawLastMoveOverlay` (xiangqi/international) `:1254-1274`; `drawPlacementLastMoveOverlay` (gomoku/go) `:1046-1060`; both use `v.isValidSquare` | yes |
| Legal-dest dots | xiangqi `drawLegalDots(Position)` `:1292-1313`; variant-aware `drawLegalDots(BoardState)` `:1320-1339` | yes |
| Resign / Draw / Take Over / Switch / Chat | `rebuildActionPanel` `:541-583`; all wired to C2S packets | yes |
| Pass button (Go only) | `rebuildActionPanel` `:557-559` (guarded by `isGo()`) | Go only ✓ |
| Layout per variant cell-size | `recomputeLayout` `:482-535` — caps: ≥19→24px, ≥15→32px, ≥9→48px, else CELL_MAX (`:498-501`) | yes |
| Board flip (FACING) | server `GameBroadcaster.sendOpenScreen` line 165-166 returns `flipped = FACING == SOUTH`; client `CChessBoardScreen.applySync` honor `boardFlipped` (squareX/Y, viewFile/Rank) `:1402-1422` | yes |

## 4. Resources

All files present in `D:\代码\qisheng-chess\common\src\main\resources\assets\qisheng_chess\`:

| Subdir | Required files | Present | Notes |
|--------|---------------|---------|-------|
| `blockstates/` | cchess, gomoku, go9, go19 | yes | all 4 |
| `models/block/` | qisheng_cchess, qisheng_gomoku, qisheng_go9, qisheng_go19 | yes | all 4 |
| `models/item/` | cchess, gomoku, go9, go19 | yes | all 4 |
| `textures/block/` | qisheng_cchess.png + _top + gomoku_top + go9_top + go19_top | yes | 5 files |
| `textures/item/` | cchess, gomoku, go9, go19 | yes | 4 files |
| `lang/` | zh_cn.json, en_us.json | yes | 153 lines each |

### I18n parity
Both lang files contain the same 139 keys (verified by line-by-line key extraction; `KEYS MATCH`). Both include block/item names, the chess-piece letter set (K/Q/R/B/N/P), every variant name, river text, chat hint, draw/switch/resign/pass prompts, role/turn/state/status strings, the qisheng.chess.cmd.* command feedback set, the qisheng.chess.server.* interaction feedback set, and the international placeholder lines (`qisheng.chess.screen.variant.international.placeholder.{line1,line2}`).

### Creative tab
`ModCreativeTabs.populateTabs()` `D:\代码\qisheng-chess\common\src\main\java\com\qisheng\chess\item\ModCreativeTabs.java:46-51` appends all four blocks: CCHESS, GOMOKU, GO9, GO19. Tab icon is `ModItems.CCHESS` (line 34). Tab id `qisheng_chess` matches `itemGroup.qisheng_chess` lang key.

### Block registrations
`ModBlocks.java:28-38` registers all four under `qisheng_chess` namespace. Each concrete block pins its variant id: `CChessBoardBlock.VARIANT_ID = "xiangqi"` (`CChessBoardBlock.java:21`), `GomokuBoardBlock.VARIANT_ID = "gomoku"` (`GomokuBoardBlock.java:24-25`), `GoBoard9Block.getVariantId` → "go9" (`GoBoard9Block.java:23-30`), `GoBoard19Block` → "go19". `BoardRegistry.java:38-42` registers all 5 variant instances (xiangqi, international, gomoku, go9, go19).

## 5. Broken logic / bugs found

### 5.1 Dead code: drawInternationalPlaceholder
`CChessBoardScreen.java:827-834` defines `drawInternationalPlaceholder()`. The stale comment at `:821-826` claims this is "the only GUI signal the player gets" and that GUI ships in v0.3.2. **It is not called anywhere** — render() at `CChessBoardScreen.java:796-798` directly calls `drawInternationalBoard()`, which renders the real 8x8 board. Either delete the dead method or update the comment to note the renderer is now active.

### 5.2 Blockstate has no `facing` variants — pure cosmetic
`D:\代码\qisheng-chess\common\src\main\resources\assets\qisheng_chess\blockstates\cchess.json` (and gomoku/go9/go19) all have only:
```json
"variants": { "": { "model": "qisheng_chess:block/qisheng_cchess" } }
```
The `FACING` BlockStateProperty (set in `AbstractChessBoardBlock.java:66`, used by `getStateForPlacement` line 101-106) is stored but never rendered — there are no `facing=north|south|east|west` mappings, so rotating the block in-world produces no visual change. The GUI's "flipped" view honors FACING via `GameBroadcaster.java:165-166`, so the player sees the correct perspective, but the world block itself always faces the same direction. Since the block model uses identical textures for all four sides (verified at `models/block/qisheng_cchess.json:6-9`), the visual effect is null — this is not a functional bug, but it's a missed opportunity. Low-priority.

### 5.3 Orphan texture: qisheng_cchess.png
`textures/block/qisheng_cchess.png` exists but is **not referenced** by any model JSON. Only `qisheng_cchess_top.png` is used (see `models/block/qisheng_cchess.json:5`). The bare file is unused. Either delete the file or add it to the model (e.g., as a "side" variant for the cchess block).

### 5.4 9x9 Go has 1 hoshi instead of standard 4
`drawStarPoints()` `CChessBoardScreen.java:1026-1027`:
```java
if (cMax == 9 && rMax == 9) {
    hoshi = new int[][]{{4, 4}};
}
```
Standard 9x9 Go has 4 hoshi at (2,2)/(2,6)/(6,2)/(6,6). Only the center (4,4) is drawn. The task description's spec ("4 star points" for go9) is the standard. Cosmetic — does not affect gameplay.

### 5.5 Inconsistent variant-id check in render dispatch
`CChessBoardScreen.java:796`:
```java
} else if ("international".equals(this.variantId)) {
```
uses raw string compare instead of the `isInternational()` helper pattern used elsewhere (`isXiangqi()` `:708`, `isGomoku()` `:713`, `isGo()` `:718`). Functionally correct but inconsistent. Add an `isInternational()` helper for symmetry.

## 6. Fix proposals with file:line refs

| # | Severity | File | Line | Fix |
|---|----------|------|------|-----|
| 1 | low (dead code) | `CChessBoardScreen.java` | 821-834 | Delete `drawInternationalPlaceholder()` method or update its javadoc to note it is no longer the active renderer. |
| 2 | low (cosmetic) | `blockstates/cchess.json` (and gomoku/go9/go19) | 1-5 | Optionally add `facing=north/south/east/west` variants pointing at the same model so the FACING property is honored visually. Requires distinct side textures, which the current models lack. |
| 3 | low (orphan) | `textures/block/qisheng_cchess.png` | — | Delete or repurpose. Currently unreferenced. |
| 4 | low (cosmetic) | `CChessBoardScreen.java` | 1026-1027 | Replace `hoshi = new int[][]{{4, 4}}` with `{{2,2},{2,6},{6,2},{6,6}}` for standard 9x9 hoshi. |
| 5 | trivial | `CChessBoardScreen.java` | 796 | Replace `"international".equals(this.variantId)` with an `isInternational()` helper used consistently with `isXiangqi/isGomoku/isGo`. |

## 7. Tests-not-passing (functional verification)

I did not run unit/integration tests in this read-only audit. The relevant test classes exist (`common/src/test/java/com/qisheng/chess/engine/go/GoVariantTest.java` is one — verified `tryPass` / `applyPass` paths exist in source). Recommended manual verification on a built instance:

1. Place a cchess block → right-click as Player A → join red → right-click as Player B → confirm second player joins black and state flips to PLAYING.
2. Play xiangqi to checkmate via `/qisheng select …` + `/qisheng move …` (or GUI clicks) → confirm `qisheng.chess.game.over.red_win` / `black_win` broadcasts and the GUI shows the result.
3. Place a go9 block → right-click → confirm Pass button appears only for Go (not xiangqi/gomoku/international).
4. Pass twice in Go → confirm game-over broadcast with score popup.
5. Place a gomoku block → 5-in-row → confirm BLACK_WIN/RED_WIN picks the correct color (this is the variant-specific branch in GameSession.java:236-239; passing it correctly verifies the Gomoku winner dispatch).
6. Force a halfmoveClock=100 position in international → confirm DRAW.
7. Trigger threefold repetition in international → confirm DRAW (relies on `positionHistory` maintained in `InternationalChessVariant.makeMove()` `:817-819`).

No playthrough-blocking issues found. All five variants play end-to-end.
