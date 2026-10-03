# Qisheng Chess — AI / Engine Audit

Scope: `pvp/PvcController.java`, `engine/ChineseChessEngine.java`, `engine/BoardVariant.java`, and the four variant shims (`xiangqi`, `international`, `gomoku`, `go`). Philosophy in scope: *simple and easy to use*.

Severity legend: **HIGH** = broken / embarrassing AI, **MEDIUM** = real limitation or foot-gun, **LOW** = polish.

---

## 1. Current AI quality per variant

### 1.1 Xiangqi — `xiangqi/XiangqiVariant.java` — **quality: GOOD**

- **Engine**: real alpha-beta via `engine/xqwlight/Search.java` (a Java port of Morning Yellow's XiangQi Wizard Light, a respected amateur engine).
- **What runs** (`PvcController.java:144`):
  ```
  variant.searchBestMove(fen, /*depth=*/19, THINK_MILLIS);
  ```
  Iterative deepening 1 → 19 plies, **self-bounded by 600 ms** at every 1024-node checkpoint (`Search.java:86-95`). Single deep iteration cannot overrun the budget — explicitly tested by `SearchTimeBudgetTest.searchStopsNearItsBudget`.
- **Strengths**: PVS with null-move pruning, killer/history heuristics, 16k-slot transposition table (`ChineseChessEngine.java:159` → `SEARCH_HASH_LEVEL=14`), repetition handling, mate-distance pruning.
- **Weaknesses**:
  - Depth 19 is overkill — the time budget will always trip first. Cosmetic; harmless.
  - No opening book: `Position.bookSize == 0` by default (`xqwlight/Position.java:432`). The first `searchMain` iter queries `pos.bookMove()` (`Search.java:461`), returns 0, then falls into ID. So opening play is decided by raw search.
  - Hashtable is 1<<14 = **16 384 entries ≈ 400 KB**. At 600 ms the search can refill it many times over; cache churn is possible. Cheap to bump to 16 or 17 if memory allows.
- **Net**: a competent amateur xiangqi opponent. Fits the "simple and easy to use" goal.

### 1.2 International chess — `international/InternationalChessVariant.java` — **HIGH severity**

- `searchBestMove` (line 172-177) is literally `return firstLegalMove(fen)`. There is no search.
- `firstLegalMove` (line 179-191) iterates `generateAllMoves` in piece-rank order (rank 0..7, file 0..7) and returns the first non-pin / non-self-check move.
- **Observable behavior**: on move 1 the AI plays whatever the very first generated legal move is — for white that is `a2→a3` (pawn pseudo-gen iterates by rank, then by file; rank 0 pieces generate first, but their moves also iterate dst by file then rank; the first legal pawn push is `a2→a3`). The opponent immediately punishes this with `a7→a5` or `e7→e5`. The AI will drop pieces, hang mates-in-one, walk into forks.
- **Why this is so weak**: there is no capture preference, no check evasion check, no piece-value awareness. The opening book is empty.
- **Fix viability**: the surrounding plumbing (FEN, canMove, wouldExposeKing, isInCheck, isSquareAttacked) is already there. A **1-ply capture-or-check-evaluation** would take ~30 lines and turn the AI from "random" into "doesn't hang pieces". A 2-ply quiescence search would be another ~60 lines and would beat most casual humans. Real alpha-beta is the v0.3.2 milestone already noted in the file's javadoc (line 20).

### 1.3 Gomoku — `gomoku/GomokuVariant.java` — **HIGH severity**

- `searchBestMove` (line 183-185) → `firstLegalMove` (line 187-195).
- `firstLegalMove` walks the 225-square array and returns the first empty intersection. Order is `sq = file + rank*15`, so it always plays the top-left corner first (`sq=0`, file 0 rank 0 = a15).
- **Consequence**: the AI will not detect a winning 4-in-a-row on its next move, will not block opponent's open-3 or open-4, will not extend a 3 of its own into an open-4. A player who understands "make two open-3s to force a win" wins every game against this AI on move 6 or 7.
- **Why this matters more than international**: gomoku has a much smaller move space (~225 vs ~1500 effective chess moves per turn) and a much simpler evaluation (count runs of 4+). A 1-ply win-or-block detector is essentially free.
- **Fix viability**: ~80 lines. Threat-pattern scan (look for own 4-in-a-row, opponent open-3 / open-4 / 4-in-a-row) before falling back to first empty.

### 1.4 Go — `go/GoVariant.java` — **HIGH severity**

- `searchBestMove` (line 289-291) → `firstLegalMove` (line 293-305).
- `firstLegalMove` walks `rank size-1 → 0`, `file 0 → size-1` and returns the first `canMove`-legal placement. So the AI plays a fixed corner regardless of the position.
- **What is *not* zero already**: the `canMove` filter (line 187-195) does block suicide and ko, so the AI will not crash or play an illegal stone. That is the floor.
- **What the AI misses**: obvious atari captures (a single opponent stone with one liberty adjacent to empty), extension of own groups one move from capture, and eye-filling suicide on the opponent's behalf (the `canMove` rule blocks filling own eyes but doesn't prevent placing a stone adjacent to its own atari group).
- **Fix viability**: ~60 lines for capture-or-extend priority (look for "if I play here I capture ≥1 enemy stone" → play; else "if I play here my group gains ≥2 liberties" → play; else first empty). Eye-shape detection (don't fill own real eye) is harder and out of scope for a "simple" philosophy.

---

## 2. PvcController design

### 2.1 Single-threaded worker — `PvcController.java:90-96`

**Verdict: keep, but expose as configuration.** Severity: **MEDIUM**.

- **What it does**: one daemon thread named `qisheng-chess-engine`, `NORM_PRIORITY - 1`, serializes *all* PVC searches across the server.
- **Why this is the right default**: a 4-core server running 8 simultaneous PVC xiangqi games at 600 ms/move would saturate every core. One thread caps the worst case at "next move starts the moment the previous one finishes".
- **Cost**: two concurrent boards finish their moves in sequence rather than parallel. With `THINK_MILLIS=600 + MIN_REPLY_DELAY=400 = 1000 ms/move`, the second board waits ~2 s for its reply instead of ~1 s. Acceptable.
- **Suggestion**: make the pool size configurable through a `ModConfig` integer `pvc.parallelSearches` (default 1), with `Executors.newFixedThreadPool(n)` instead of `newSingleThreadExecutor`. Per-board priority or starvation is then a per-game concern, not a global one.

### 2.2 `THINK_MILLIS = 600` — `PvcController.java:63`

**Verdict: per-variant budget, not a global one.** Severity: **MEDIUM**.

- Xiangqi: 600 ms is right — the engine actually consumes it (see `SearchTimeBudgetTest.searchStopsNearItsBudget`).
- International: 600 ms is pure waste — the variant returns in microseconds via `firstLegalMove`. Even after the 1-ply improvement in §3.2, 100 ms is plenty.
- Gomoku: 600 ms is overkill. A 1-ply threat scan finishes in <1 ms even on a 15×15 board.
- Go: 600 ms is overkill. A capture-or-extend filter is also <1 ms.
- **Suggestion**: store a per-variant budget in `BoardVariant` (default method returning 600) so xiangqi keeps its 600 and the trivial variants drop to e.g. 80 ms. The wall-clock `MIN_REPLY_DELAY` is a *separate* concern (it's there to keep the board from visibly flipping twice in the same frame) and should stay variant-independent or at least capped at e.g. 200 ms for the trivial games.

### 2.3 `MIN_REPLY_DELAY_MILLIS = 400` — `PvcController.java:66`

**Verdict: too long for trivial games.** Severity: **LOW**.

- A firstLegalMove in international/gomoku/go finishes in microseconds; sleeping an extra 400 ms before the reply makes the AI feel *unresponsive*, not "thinking".
- **Suggestion**: per-variant floor in `BoardVariant.minReplyDelayMillis()` (default 400, gomoku/go/international 80–150 ms).

### 2.4 `IN_FLIGHT_TIMEOUT_NANOS = 30_000_000_000` (30 s) — `PvcController.java:77`

**Verdict: appropriate.** Severity: **LOW**.

- 30 s is several orders of magnitude longer than a worst-case xiangqi ID run (16 plies × 600 ms budget = 10 s theoretical). The only way to hit it is a JVM crash or a NIO stall mid-search.
- The warning log at line 113-114 is the right escape hatch — the board becomes schedulable again rather than stuck forever.

### 2.5 Worker-thread per-game serialization — `PvcController.java:90`

**Verdict: covered above.** The single-thread design is intentional; the recommended change is making the pool size configurable rather than replacing the architecture.

---

## 3. Move time / cancellation

### 3.1 Does cancellation work cleanly? — **YES, no change needed.** Severity: **LOW** (informational).

- **Player disconnects mid-search**: the search runs to completion on the worker thread (xiangqi self-bounded at 600 ms; trivial variants finish in <1 ms). The result is delivered via `server.execute(() -> play(...))` (line 172). On the tick thread, `play` re-derives `level`, `session`, `isComputerToMove()` and bails if anything changed (line 184-201). A disconnect that turned the board back to PVP, or a `/qisheng reset`, or a human resignation, simply makes the reply a no-op.
- **Variant flip mid-search** (line 195-201): if `setMode` changed the variant, the cached `variantId` no longer matches the session's; the reply re-broadcasts the new state and lets the next turn pick the right engine.
- **Server stop mid-search**: the worker thread holds no locks and the JVM exits; the daemon flag (line 92) means it doesn't block shutdown.
- **What is *not* there**: a hard cancellation channel. If you wanted "kill the search when the player disconnects", you'd need a `Future` per task and `future.cancel(true)` from `maybeSchedule`. Not worth the complexity for a 600 ms budget.

### 3.2 Race conditions between worker and main thread

**Verdict: none observed.** Severity: **LOW**.

- The FEN is the only thing crossing the thread boundary (line 129); the worker parses a *fresh* `Position` (or `IntChessBoard` / `GomokuBoard` / `GoBoard`) onto a private board — explicitly called out in `BoardVariant.java:30-31` and `ChineseChessEngine.java:171-174`. The session board is never shared with the worker.
- The reply path captures `finalMove` and `variantId` into local finals (line 170) before crossing back to the main thread.
- `IN_FLIGHT` is a `ConcurrentHashMap` (line 80) — the only shared state. `maybeSchedule` is `put` (line 116); `play` and `searchThenPlay`'s error paths are `remove` (lines 120, 133, 174, 182, 238). Both `get` then `put` are guarded against stale entries (line 110-115). A board that is "in flight" gets skipped, which is the intended serialization.
- **One subtle edge**: if `play` runs and `IN_FLIGHT.remove(key)` (line 182) happens *after* a new `maybeSchedule` re-inserted it (line 116), the new search's marker is wiped and a third call could schedule again before the second search replies. The 30 s timeout catches this; the realistic 1 s round trip makes it effectively impossible.
- **Minor**: `IN_FLIGHT.put(key, now)` overwrites a previous marker's timestamp (line 116). This is intentional (re-scheduling after timeout), but the `else`-branch on line 116 would be clearer if it were explicit.

---

## 4. `searchBestMove` / `firstLegalMove` API

### 4.1 Should `firstLegalMove` be replaced with a "basic AI" helper per variant? — **YES for international, gomoku, go.** Severity: **HIGH** for those three.

- The interface contract (`BoardVariant.java:118-122`) is fine; `firstLegalMove` belongs there as the *fallback*, not as the *AI*. `PvcController.searchThenPlay` (line 150-158) correctly treats it as a fallback when `searchBestMove` returns NONE.
- For variants that don't have a real engine, the right place to put a 1-ply improvement is in **their own `searchBestMove`** — `PvcController` should not know that gomoku does a "win-or-block" check; that knowledge lives in the variant.
- **Design**: add a default method on `BoardVariant` so future variants inherit a sensible behavior:

  ```java
  // In BoardVariant.java, after line 122
  default Move searchBestMove(String fen, int depth, int millis) {
      return firstLegalMove(fen);
  }
  ```

  xiangqi overrides it (already does). International/gomoku/go can keep the default *or* override it with their 1-ply. This keeps the contract minimal and the optional improvement local to the variant.

### 4.2 Simplest AI improvement per variant (still in the "simple" philosophy)

#### 4.2.1 Gomoku — 1-ply "win-or-block" — severity **HIGH**

**Where**: `gomoku/GomokuVariant.java`, replace `searchBestMove` (line 183-185) and add a helper.

**Approach**: scan empty squares, classify each by what placing there does to (a) own 4-in-a-row runs and (b) opponent open-3 / 4-in-a-row. Pick the highest-priority class.

```java
@Override public Move searchBestMove(String fen, int depth, int millis) {
    BoardState state = parseState(fen);
    if (!(state instanceof GomokuBoard b)) return Move.NONE;
    if (b.winner != GomokuBoard.EMPTY) return Move.NONE;

    byte me = (byte) (b.sdPlayer == 0 ? GomokuBoard.BLACK : GomokuBoard.WHITE);
    byte opp = (byte) (me == GomokuBoard.BLACK ? GomokuBoard.WHITE : GomokuBoard.BLACK);

    int bestPriority = Integer.MIN_VALUE;
    int bestSq = -1;
    for (int sq = 0; sq < totalSquares(); sq++) {
        if (b.squares[sq] != GomokuBoard.EMPTY) continue;
        int p = placementPriority(b, sq, me, opp);
        if (p > bestPriority) { bestPriority = p; bestSq = sq; }
    }
    return bestSq >= 0 ? new Move(bestSq, bestSq) : Move.NONE;
}

private static int placementPriority(GomokuBoard b, int sq, byte me, byte opp) {
    // Highest: own run of 4 (winning move).
    // Next:    block opp run of 4.
    // Next:    own open-3 (creates two winning threats at once).
    // Next:    block opp open-3.
    // Else:    0 (use firstLegalMove's fallback order).
    ...
}
```

**Tradeoff**: ~80 lines, runs in <1 ms on the 15×15 board, ends the "AI loses to a determined 5-year-old" problem. Test additions: `GomokuVariantTest. aiBlocksOpenFour`, `aiPlaysWinningMove`.

#### 4.2.2 Go — 1-ply "capture / extend / avoid-fill-own-eye" — severity **HIGH**

**Where**: `go/GoVariant.java`, replace `searchBestMove` (line 289-291).

**Approach**: scan legal placements, pick the highest-priority class.

```java
@Override public Move searchBestMove(String fen, int depth, int millis) {
    BoardState state = parseState(fen);
    if (!(state instanceof GoBoard b)) return Move.NONE;
    if (b.finished) return Move.NONE;

    int bestPriority = Integer.MIN_VALUE;
    int bestSq = -1;
    for (int sq = 0; sq < b.size * b.size; sq++) {
        if (!canMove(b, sq, sq)) continue;   // already gates suicide + ko
        int p = placementPriority(b, sq);
        if (p > bestPriority) { bestPriority = p; bestSq = sq; }
    }
    return bestSq >= 0 ? new Move(bestSq, bestSq) : Move.NONE;
}

private static int placementPriority(GoBoard b, int sq) {
    // Class 1: capture ≥1 enemy stone.
    // Class 2: own group atari → extend (group atari if any adjacent enemy group has 1 liberty after placement).
    // Class 3: play adjacent to own stone (extension / influence) when not adjacent to enemy.
    // Class 4: don't fill a real eye of our own colour (lightest check: don't play a square
    //          whose 4 neighbours are all of our own colour).
    ...
}
```

**Tradeoff**: ~60 lines. Eye-shape detection is hard; the "4 same-colour neighbours" heuristic is a *visible* approximation that does stop the AI from suiciding its own eye in obvious cases. Real eye recognition is a future milestone. The capture-and-extend improvement alone moves the AI from "plays a15 every game" to "actually fights back".

#### 4.2.3 International — 1-ply "capture / check / avoid-hang" — severity **HIGH**

**Where**: `international/InternationalChessVariant.java`, replace `searchBestMove` (line 172-177).

**Approach**: enumerate legal moves, score by (most-valuable-victim − least-valuable-aggressor) plus bonuses for giving check. Reuse the existing `isInCheck`/`isSquareAttacked` plumbing.

```java
private static final int[] PIECE_VALUES = {0, 1, 3, 3, 5, 9, 0 /*king*/};

@Override public Move searchBestMove(String fen, int depth, int millis) {
    BoardState state = parseState(fen);
    if (!(state instanceof IntChessBoard b)) return Move.NONE;

    int bestScore = Integer.MIN_VALUE;
    int bestMv = -1;
    int[] mvs = new int[128];
    int n = generateAllMoves(b, mvs);
    for (int i = 0; i < n; i++) {
        int src = srcOf(mvs[i]), dst = dstOf(mvs[i]);
        if (!canMove(b, src, dst)) continue;          // filters self-check
        int score = moveScore(b, src, dst);
        if (score > bestScore) { bestScore = score; bestMv = mvs[i]; }
    }
    return bestMv < 0 ? Move.NONE : new Move(srcOf(bestMv), dstOf(bestMv));
}

private static int moveScore(IntChessBoard b, int src, int dst) {
    int score = 0;
    byte victim = b.squares[dst];
    if (victim != 0) score += 10 * PIECE_VALUES[IntChessBoard.typeOf(victim)];
    byte mover = b.squares[src];
    score -= PIECE_VALUES[IntChessBoard.typeOf(mover)];     // discourage hanging mover
    // (Optional) Bonus for giving check: simulate move, call isInCheck, undo.
    return score;
}
```

**Tradeoff**: ~40 lines. MVV-LVA is the textbook first heuristic. The AI stops hanging pieces and starts taking free ones — a *massive* perceived strength jump without touching search. Real alpha-beta is the v0.3.2 milestone.

---

## 5. Concrete suggestions — prioritized

| # | Severity | File | Lines | Change |
|---|----------|------|-------|--------|
| 1 | HIGH | `pvp/PvcController.java` | 63 | Add a `private static int thinkMillisFor(BoardVariant)` (default 600; gomoku/go/international 80). |
| 2 | HIGH | `pvp/PvcController.java` | 66 | Add a `private static long replyDelayFor(BoardVariant)` (default 400; gomoku/go/international 120). |
| 3 | HIGH | `engine/gomoku/GomokuVariant.java` | 183-185 | Override `searchBestMove` with the 1-ply win-or-block helper from §4.2.1. |
| 4 | HIGH | `engine/go/GoVariant.java` | 289-291 | Override `searchBestMove` with the capture-or-extend helper from §4.2.2. |
| 5 | HIGH | `engine/international/InternationalChessVariant.java` | 172-177 | Override `searchBestMove` with the MVV-LVA + check bonus from §4.2.3. |
| 6 | MEDIUM | `engine/BoardVariant.java` | after 122 | Add a `default Move searchBestMove(String fen, int depth, int millis) { return firstLegalMove(fen); }` so variants only opt in to better AI. |
| 7 | MEDIUM | `pvp/PvcController.java` | 90-96 | Replace `newSingleThreadExecutor` with a fixed-size pool driven by a `ModConfig` integer; keep `setPriority(NORM_PRIORITY - 1)` and daemon flag. |
| 8 | LOW | `pvp/PvcController.java` | 116 | Make the `IN_FLIGHT.put` overwrite explicit (`else { IN_FLIGHT.put(key, now); }`) for clarity. |
| 9 | LOW | `engine/ChineseChessEngine.java` | 159 | Consider bumping `SEARCH_HASH_LEVEL` from 14 to 16 (~6 MB → hash pressure drops). Validate in `SearchTimeBudgetTest`. |
| 10 | LOW | `engine/xiangqi/XiangqiVariant.java` | 107 | The `depth=19` cap in `PvcController` is unused; `LIMIT_DEPTH=64` in `Search.java:29` already caps it. Either lower `LIMIT_DEPTH` (changes xqwlight contract) or stop passing depth from the call site. Cosmetic. |

### Tests to add alongside the changes

- `GomokuVariantTest.aiTakesWinningFour`
- `GomokuVariantTest.aiBlocksOpponentOpenFour`
- `GoVariantTest.aiTakesObviousCapture`
- `GoVariantTest.aiExtendsOwnAtariGroup`
- `InternationalChessVariantTest.aiPrefersCaptureOverQuietMove`
- `InternationalChessVariantTest.aiDoesNotHangQueenForPawn`

---

## 6. Bottom-line summary

- **Xiangqi is the only variant with a real AI**, and it's a respectable one. Keep it.
- **The other three variants ship an AI that is essentially random**, which is a **HIGH-severity product problem**: a player who learns gomoku in five minutes can beat the gomoku AI deterministically, and the same for the others. This is the single most important fix in this audit.
- **Per-variant time budgets** are an easy follow-up that makes the trivial games feel snappy without slowing xiangqi.
- **The PvcController architecture is sound**. The single-thread serialization, the 30 s in-flight timeout, and the FEN-only thread boundary are all well-designed. The only MEDIUM-severity change is making the pool size configurable so a future user with a 16-core dedicated server can opt in to parallelism.
- **No race conditions or cancellation bugs were observed**.
