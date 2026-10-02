/*
Search.java - Source Code for XiangQi Wizard Light, Part II

XiangQi Wizard Light - a Chinese Chess Program for Java ME
Designed by Morning Yellow, Version: 1.70, Last Modified: Mar. 2013
Copyright (C) 2004-2013 www.xqbase.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program; if not, write to the Free Software Foundation, Inc.,
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/
package com.qisheng.chess.engine.xqwlight;

@SuppressWarnings("all")
public class Search {
    private static final int HASH_ALPHA = 1;
    private static final int HASH_BETA = 2;
    private static final int HASH_PV = 3;
    private static final int LIMIT_DEPTH = 64;
    private static final int NULL_DEPTH = 2;
    private static final int RANDOM_MASK = 7;
    private static final int MAX_GEN_MOVES = Position.MAX_GEN_MOVES;
    private static final int MATE_VALUE = Position.MATE_VALUE;
    private static final int BAN_VALUE = Position.BAN_VALUE;
    private static final int WIN_VALUE = Position.WIN_VALUE;

    private int hashMask, mvResult, allNodes, allMillis;
    private HashItem[] hashTable;
    Position pos;
    int[] historyTable = new int[4096];
    int[][] mvKiller = new int[LIMIT_DEPTH][2];

    /**
     * Absolute {@code System.currentTimeMillis()} deadline for the running search,
     * or {@code 0} when no budget was given.
     */
    private long deadline = 0L;

    /** Latched as soon as the deadline is exceeded; unwinds the recursion. */
    private boolean stopped = false;

    /**
     * Nodes between clock reads. {@code System.currentTimeMillis()} costs about as
     * much as a handful of nodes, so it must not be called per node. Must be a
     * power of two — the check is a mask on {@link #allNodes}.
     */
    private static final int CHECK_NODES = 1024;

    /**
     * Value returned by an aborted subtree. It equals the existing "no best value
     * yet" sentinel used by {@link #searchFull}/{@link #searchRoot}, so a parent
     * naturally discards an aborted line instead of adopting a bogus score.
     */
    private static final int ABORTED = -MATE_VALUE;

    public Search(Position pos, int hashLevel) {
        this.pos = pos;
        hashMask = (1 << hashLevel) - 1;
        hashTable = new HashItem[hashMask + 1];
        for (int i = 0; i <= hashMask; i++) {
            hashTable[i] = new HashItem();
        }
    }

    /**
     * True when the time budget is used up.
     *
     * <p>Only reads the clock every {@link #CHECK_NODES} nodes. The first node of a
     * search never trips it, so a search always gets at least one node in.
     *
     * <p>Aborting is safe for the position: every frame of {@code searchFull} /
     * {@code searchQuiesc} that can observe {@code stopped} is at a point where its
     * own {@code makeMove} has already been undone, so the board is never left in a
     * half-made state.
     */
    private boolean outOfTime() {
        if (stopped) return true;
        if (deadline == 0L) return false;
        if ((allNodes & (CHECK_NODES - 1)) != 0) return false;
        if (System.currentTimeMillis() >= deadline) {
            stopped = true;
            return true;
        }
        return false;
    }

    private HashItem getHashItem() {
        return hashTable[pos.zobristKey & hashMask];
    }

    private int probeHash(int vlAlpha, int vlBeta, int depth, int[] mv) {
        HashItem hash = getHashItem();
        if (hash.zobristLock != pos.zobristLock) {
            mv[0] = 0;
            return -MATE_VALUE;
        }
        mv[0] = hash.mv;
        boolean mate = false;
        if (hash.vl > WIN_VALUE) {
            if (hash.vl <= BAN_VALUE) {
                return -MATE_VALUE;
            }
            hash.vl -= (short) pos.distance;
            mate = true;
        } else if (hash.vl < -WIN_VALUE) {
            if (hash.vl >= -BAN_VALUE) {
                return -MATE_VALUE;
            }
            hash.vl += (short) pos.distance;
            mate = true;
        } else if (hash.vl == pos.drawValue()) {
            return -MATE_VALUE;
        }
        if (hash.depth >= depth || mate) {
            if (hash.flag == HASH_BETA) {
                return (hash.vl >= vlBeta ? hash.vl : -MATE_VALUE);
            } else if (hash.flag == HASH_ALPHA) {
                return (hash.vl <= vlAlpha ? hash.vl : -MATE_VALUE);
            }
            return hash.vl;
        }
        return -MATE_VALUE;
    }

    private void recordHash(int flag, int vl, int depth, int mv) {
        HashItem hash = getHashItem();
        if (hash.depth > depth) {
            return;
        }
        hash.flag = (byte) flag;
        hash.depth = (byte) depth;
        if (vl > WIN_VALUE) {
            if (mv == 0 && vl <= BAN_VALUE) {
                return;
            }
            hash.vl = (short) (vl + pos.distance);
        } else if (vl < -WIN_VALUE) {
            if (mv == 0 && vl >= -BAN_VALUE) {
                return;
            }
            hash.vl = (short) (vl - pos.distance);
        } else if (vl == pos.drawValue() && mv == 0) {
            return;
        } else {
            hash.vl = (short) vl;
        }
        hash.mv = mv;
        hash.zobristLock = pos.zobristLock;
    }

    private class SortItem {
        private static final int PHASE_HASH = 0;
        private static final int PHASE_KILLER_1 = 1;
        private static final int PHASE_KILLER_2 = 2;
        private static final int PHASE_GEN_MOVES = 3;
        private static final int PHASE_REST = 4;

        private int index, moves, phase;
        private int mvHash, mvKiller1, mvKiller2;
        private int[] mvs, vls;

        boolean singleReply = false;

        SortItem(int mvHash) {
            if (!pos.inCheck()) {
                phase = PHASE_HASH;
                this.mvHash = mvHash;
                mvKiller1 = mvKiller[pos.distance][0];
                mvKiller2 = mvKiller[pos.distance][1];
                return;
            }
            phase = PHASE_REST;
            this.mvHash = mvKiller1 = mvKiller2 = 0;
            mvs = new int[MAX_GEN_MOVES];
            vls = new int[MAX_GEN_MOVES];
            moves = 0;
            int[] mvsAll = new int[MAX_GEN_MOVES];
            int numAll = pos.generateAllMoves(mvsAll);
            for (int i = 0; i < numAll; i++) {
                int mv = mvsAll[i];
                if (!pos.makeMove(mv)) {
                    continue;
                }
                pos.undoMakeMove();
                mvs[moves] = mv;
                vls[moves] = mv == mvHash ? Integer.MAX_VALUE : historyTable[pos.historyIndex(mv)];
                moves++;
            }
            Util.shellSort(mvs, vls, 0, moves);
            index = 0;
            singleReply = moves == 1;
        }

        int next() {
            if (phase == PHASE_HASH) {
                phase = PHASE_KILLER_1;
                if (mvHash > 0) {
                    return mvHash;
                }
            }
            if (phase == PHASE_KILLER_1) {
                phase = PHASE_KILLER_2;
                if (mvKiller1 != mvHash && mvKiller1 > 0 && pos.legalMove(mvKiller1)) {
                    return mvKiller1;
                }
            }
            if (phase == PHASE_KILLER_2) {
                phase = PHASE_GEN_MOVES;
                if (mvKiller2 != mvHash && mvKiller2 > 0 && pos.legalMove(mvKiller2)) {
                    return mvKiller2;
                }
            }
            if (phase == PHASE_GEN_MOVES) {
                phase = PHASE_REST;
                mvs = new int[MAX_GEN_MOVES];
                vls = new int[MAX_GEN_MOVES];
                moves = pos.generateAllMoves(mvs);
                for (int i = 0; i < moves; i++) {
                    vls[i] = historyTable[pos.historyIndex(mvs[i])];
                }
                Util.shellSort(mvs, vls, 0, moves);
                index = 0;
            }
            while (index < moves) {
                int mv = mvs[index];
                index++;
                if (mv != mvHash && mv != mvKiller1 && mv != mvKiller2) {
                    return mv;
                }
            }
            return 0;
        }
    }

    private void setBestMove(int mv, int depth) {
        historyTable[pos.historyIndex(mv)] += depth * depth;
        int[] killers = mvKiller[pos.distance];
        if (killers[0] != mv) {
            killers[1] = killers[0];
            killers[0] = mv;
        }
    }

    private int searchQuiesc(int vlAlpha_, int vlBeta) {
        int vlAlpha = vlAlpha_;
        allNodes++;
        if (outOfTime()) return ABORTED;
        int vl = pos.mateValue();
        if (vl >= vlBeta) {
            return vl;
        }
        int vlRep = pos.repStatus();
        if (vlRep > 0) {
            return pos.repValue(vlRep);
        }
        if (pos.distance == LIMIT_DEPTH) {
            return pos.evaluate();
        }
        int vlBest = -MATE_VALUE;
        int genMoves;
        int[] mvs = new int[MAX_GEN_MOVES];
        if (pos.inCheck()) {
            genMoves = pos.generateAllMoves(mvs);
            int[] vls = new int[MAX_GEN_MOVES];
            for (int i = 0; i < genMoves; i++) {
                vls[i] = historyTable[pos.historyIndex(mvs[i])];
            }
            Util.shellSort(mvs, vls, 0, genMoves);
        } else {
            vl = pos.evaluate();
            if (vl > vlBest) {
                if (vl >= vlBeta) {
                    return vl;
                }
                vlBest = vl;
                vlAlpha = Math.max(vl, vlAlpha);
            }
            int[] vls = new int[MAX_GEN_MOVES];
            genMoves = pos.generateMoves(mvs, vls);
            Util.shellSort(mvs, vls, 0, genMoves);
            for (int i = 0; i < genMoves; i++) {
                if (vls[i] < 10 || (vls[i] < 20 && Position.HOME_HALF(Position.DST(mvs[i]), pos.sdPlayer))) {
                    genMoves = i;
                    break;
                }
            }
        }
        for (int i = 0; i < genMoves; i++) {
            if (!pos.makeMove(mvs[i])) {
                continue;
            }
            vl = -searchQuiesc(-vlBeta, -vlAlpha);
            pos.undoMakeMove();
            if (vl > vlBest) {
                if (vl >= vlBeta) {
                    return vl;
                }
                vlBest = vl;
                vlAlpha = Math.max(vl, vlAlpha);
            }
        }
        // Do not report "checkmate" for a position we simply ran out of time on.
        if (stopped) return ABORTED;
        return vlBest == -MATE_VALUE ? pos.mateValue() : vlBest;
    }

    private int searchNoNull(int vlAlpha, int vlBeta, int depth) {
        return searchFull(vlAlpha, vlBeta, depth, true);
    }

    private int searchFull(int vlAlpha, int vlBeta, int depth) {
        return searchFull(vlAlpha, vlBeta, depth, false);
    }

    private int searchFull(int vlAlpha_, int vlBeta, int depth, boolean noNull) {
        int vlAlpha = vlAlpha_;
        int vl;
        if (depth <= 0) {
            return searchQuiesc(vlAlpha, vlBeta);
        }
        allNodes++;
        if (outOfTime()) return ABORTED;
        vl = pos.mateValue();
        if (vl >= vlBeta) {
            return vl;
        }
        int vlRep = pos.repStatus();
        if (vlRep > 0) {
            return pos.repValue(vlRep);
        }
        int[] mvHash = new int[1];
        vl = probeHash(vlAlpha, vlBeta, depth, mvHash);
        if (vl > -MATE_VALUE) {
            return vl;
        }
        if (pos.distance == LIMIT_DEPTH) {
            return pos.evaluate();
        }
        if (!noNull && !pos.inCheck() && pos.nullOkay()) {
            pos.nullMove();
            vl = -searchNoNull(-vlBeta, 1 - vlBeta, depth - NULL_DEPTH - 1);
            pos.undoNullMove();
            if (vl >= vlBeta && (pos.nullSafe() || searchNoNull(vlAlpha, vlBeta, depth - NULL_DEPTH) >= vlBeta)) {
                return vl;
            }
        }
        int hashFlag = HASH_ALPHA;
        int vlBest = -MATE_VALUE;
        int mvBest = 0;
        SortItem sort = new SortItem(mvHash[0]);
        int mv;
        while ((mv = sort.next()) > 0) {
            if (!pos.makeMove(mv)) {
                continue;
            }
            int newDepth = pos.inCheck() || sort.singleReply ? depth : depth - 1;
            if (vlBest == -MATE_VALUE) {
                vl = -searchFull(-vlBeta, -vlAlpha, newDepth);
            } else {
                vl = -searchFull(-vlAlpha - 1, -vlAlpha, newDepth);
                if (vl > vlAlpha && vl < vlBeta) {
                    vl = -searchFull(-vlBeta, -vlAlpha, newDepth);
                }
            }
            pos.undoMakeMove();
            if (vl > vlBest) {
                vlBest = vl;
                if (vl >= vlBeta) {
                    hashFlag = HASH_BETA;
                    mvBest = mv;
                    break;
                }
                if (vl > vlAlpha) {
                    vlAlpha = vl;
                    hashFlag = HASH_PV;
                    mvBest = mv;
                }
            }
        }
        // An aborted search must not publish a hash entry or killer/history move —
        // those survive into the next iteration and would poison it.
        if (stopped) return ABORTED;
        if (vlBest == -MATE_VALUE) {
            return pos.mateValue();
        }
        recordHash(hashFlag, vlBest, depth, mvBest);
        if (mvBest > 0) {
            setBestMove(mvBest, depth);
        }
        return vlBest;
    }

    private int searchRoot(int depth) {
        int vlBest = -MATE_VALUE;
        SortItem sort = new SortItem(mvResult);
        int mv;
        while ((mv = sort.next()) > 0) {
            if (!pos.makeMove(mv)) {
                continue;
            }
            int newDepth = pos.inCheck() ? depth : depth - 1;
            int vl;
            if (vlBest == -MATE_VALUE) {
                vl = -searchNoNull(-MATE_VALUE, MATE_VALUE, newDepth);
            } else {
                vl = -searchFull(-vlBest - 1, -vlBest, newDepth);
                if (vl > vlBest) {
                    vl = -searchNoNull(-MATE_VALUE, -vlBest, newDepth);
                }
            }
            pos.undoMakeMove();
            if (stopped) {
                break;
            }
            if (vl > vlBest) {
                vlBest = vl;
                mvResult = mv;
                if (vlBest > -WIN_VALUE && vlBest < WIN_VALUE) {
                    vlBest += (Position.random.nextInt() & RANDOM_MASK) -
                              (Position.random.nextInt() & RANDOM_MASK);
                    vlBest = (vlBest == pos.drawValue() ? vlBest - 1 : vlBest);
                }
            }
        }
        setBestMove(mvResult, depth);
        return vlBest;
    }

    public boolean searchUnique(int vlBeta, int depth) {
        SortItem sort = new SortItem(mvResult);
        sort.next();
        int mv;
        while ((mv = sort.next()) > 0) {
            if (!pos.makeMove(mv)) {
                continue;
            }
            int vl = -searchFull(-vlBeta, 1 - vlBeta, pos.inCheck() ? depth : depth - 1);
            pos.undoMakeMove();
            if (vl >= vlBeta) {
                return false;
            }
        }
        return true;
    }

    public int searchMain(int millis) {
        return searchMain(LIMIT_DEPTH, millis);
    }

    public int searchMain(int depth, int millis) {
        mvResult = pos.bookMove();
        if (mvResult > 0) {
            pos.makeMove(mvResult);
            if (pos.repStatus(3) == 0) {
                pos.undoMakeMove();
                return mvResult;
            }
            pos.undoMakeMove();
        }
        for (int i = 0; i <= hashMask; i++) {
            HashItem hash = hashTable[i];
            hash.depth = hash.flag = 0;
            hash.vl = 0;
            hash.mv = hash.zobristLock = 0;
        }
        for (int i = 0; i < LIMIT_DEPTH; i++) {
            mvKiller[i][0] = mvKiller[i][1] = 0;
        }
        for (int i = 0; i < 4096; i++) {
            historyTable[i] = 0;
        }
        mvResult = 0;
        allNodes = 0;
        allMillis = 0;
        pos.distance = 0;
        long t = System.currentTimeMillis();
        // The budget is enforced inside the search itself, not only between iterations:
        // a single deep iteration used to be able to run for an unbounded time.
        deadline = t + Math.max(1L, (long) millis);
        stopped = false;
        try {
            for (int i = 1; i <= depth; i++) {
                int vl = searchRoot(i);
                allMillis = (int) (System.currentTimeMillis() - t);
                if (stopped || allMillis > millis) {
                    break;
                }
                if (vl > WIN_VALUE || vl < -WIN_VALUE) {
                    break;
                }
                if (searchUnique(1 - WIN_VALUE, i)) {
                    break;
                }
            }
        } finally {
            deadline = 0L;
            stopped = false;
        }
        if (allMillis <= 0) {
            allMillis = (int) (System.currentTimeMillis() - t);
        }
        if (mvResult <= 0) {
            mvResult = fallbackMove();
        }
        return mvResult;
    }

    /**
     * Returns any legal move for the side to move, or {@code 0} when the side is
     * checkmated or stalemated.
     *
     * <p>Guarantees that an aborted search still hands the caller a playable move.
     * The position is left exactly as it was found.
     */
    private int fallbackMove() {
        int[] mvs = new int[MAX_GEN_MOVES];
        int genMoves = pos.generateAllMoves(mvs);
        for (int i = 0; i < genMoves; i++) {
            if (pos.makeMove(mvs[i])) {
                pos.undoMakeMove();
                return mvs[i];
            }
        }
        return 0;
    }

    /**
     * Nodes per second, or {@code 0} when the clock never advanced far enough to
     * measure. Never divides by zero.
     */
    public int getKNPS() {
        return allMillis <= 0 ? 0 : allNodes / allMillis;
    }

    static class HashItem {
        byte depth, flag;
        short vl;
        int mv, zobristLock;
    }
}