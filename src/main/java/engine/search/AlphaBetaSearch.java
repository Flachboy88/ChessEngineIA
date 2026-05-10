package engine.search;

import core.BitboardState;
import engine.evaluation.PositionEvaluator;
import engine.tb.SyzygyTablebase;
import engine.tb.WDL;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
import rules.MagicBitboards;
import rules.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Algorithme MinMax avec élagage Alpha-Bêta — version haute performance.
 *
 * <h2>Optimisations actives</h2>
 * <ol>
 *   <li><b>Negamax alpha-bêta</b></li>
 *   <li><b>Iterative Deepening (IDDFS)</b></li>
 *   <li><b>Aspiration Windows multi-étages</b> — 50 → 150 → 500 → ∞</li>
 *   <li><b>Table de transposition (TT)</b> — EXACT/LOWER/UPPER, scores mat normalisés par ply</li>
 *   <li><b>Null Move Pruning (NMP) adaptatif</b> — R=3 si depth&lt;6, R=4 sinon</li>
 *   <li><b>Extension d'échec</b> — +1 ply, bornée à MAX_EXTENSIONS</li>
 *   <li><b>Late Move Reduction (LMR)</b> — réduction variable selon depth/movesCount</li>
 *   <li><b>PVS (Principal Variation Search)</b> — fenêtre nulle [α,α+1] sur tous les coups non-PV</li>
 *   <li><b>Futility Pruning</b> — depth 1-2, hors échec</li>
 *   <li><b>Razoring</b> — depth=1, si eval+marge &lt; alpha → quiescence directe</li>
 *   <li><b>Late Move Pruning (LMP)</b> — coupe les coups silencieux tardifs entièrement</li>
 *   <li><b>Singular Extensions</b> — +1 ply si le coup TT domine avec marge</li>
 *   <li><b>Internal Iterative Deepening (IID)</b> — recherche réduite si pas de coup TT</li>
 *   <li><b>Move ordering</b> — TT > captures SEE > promotions > killers > countermove > history</li>
 *   <li><b>Killer moves</b> — 2 par ply</li>
 *   <li><b>Countermove Heuristic</b> — table counterMove[from][to] du dernier coup adverse</li>
 *   <li><b>History Heuristic</b> — bonus depth² sur bêta-coupure</li>
 *   <li><b>History Malus (decay)</b> — malus sur les coups silencieux qui ratent</li>
 *   <li><b>SEE (Static Exchange Evaluation)</b> — tri captures + filtre quiescence</li>
 *   <li><b>Pawn Hash Table</b> — dans {@link PositionEvaluator}, évite 90 % des recalculs pions</li>
 *   <li><b>Quiescence search + Delta Pruning</b></li>
 * </ol>
 */
public final class AlphaBetaSearch {

    static final int INF = PositionEvaluator.MATE_SCORE;

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final int MAX_QUIESCENCE_DEPTH = 6;
    private static final int KILLERS_PER_PLY      = 2;
    private static final int MAX_PLY              = 64;

    // Null Move Pruning adaptatif
    private static final int NULL_MOVE_R_BASE      = 3;
    private static final int NULL_MOVE_R_DEEP      = 4;
    private static final int NULL_MOVE_R_DEPTH_THR = 6;
    private static final int NULL_MOVE_MIN_DEPTH   = 3;
    private static final int NULL_MOVE_MIN_MATERIAL = 1500;

    // Late Move Reduction
    private static final int LMR_FULL_DEPTH_MOVES = 4;
    private static final int LMR_MIN_DEPTH        = 3;

    // Late Move Pruning — seuils (coups silencieux max avant coupure complète)
    private static final int[] LMP_THRESHOLD = { 0, 8, 12, 16, 24 };

    // Futility Pruning
    private static final int FUTILITY_MARGIN_PER_DEPTH = 150;
    private static final int FUTILITY_MAX_DEPTH        = 2;

    // Razoring
    private static final int RAZOR_MARGIN = 300;

    // Aspiration Windows multi-étages
    private static final int[] ASPIRATION_WINDOWS = { 50, 150, 500, Integer.MAX_VALUE };

    // Singular Extensions
    private static final int SINGULAR_MIN_DEPTH = 6;
    private static final int SINGULAR_MARGIN    = 64;

    // IID
    private static final int IID_MIN_DEPTH = 4;

    // Seuil de mat
    private static final int MATE_THRESHOLD = INF - MAX_PLY * 2;

    // Delta Pruning (quiescence)
    private static final int DELTA_MARGIN = 200;

    // Extensions d'échec
    private static final int MAX_EXTENSIONS = 3;

    // Countermove score dans le move ordering
    private static final int COUNTERMOVE_SCORE = 700_000;

    // ── Table de transposition ────────────────────────────────────────────────

    private static final TranspositionTable TT = new TranspositionTable();

    // ── Tablebases Syzygy (partagées, configurables) ──────────────────────────

    private static volatile SyzygyTablebase tablebase = SyzygyTablebase.disabled();

    // ── Contrôle du temps ─────────────────────────────────────────────────────

    private static volatile long    deadline   = 0L;
    private static volatile boolean stopSearch = false;

    private AlphaBetaSearch() {}

    // =========================================================================
    // API publique
    // =========================================================================

    public static Move chercherMeilleurCoup(GameState state, int profondeur) {
        deadline   = 0L;
        stopSearch = false;
        BitboardState bs    = state.getBitboardState();
        List<Move>    moves = MoveGenerator.generateLegalMoves(bs);
        if (moves.isEmpty()) throw new IllegalStateException("Aucun coup légal.");
        return new Recherche(profondeur).iterativeDeepening(bs, moves);
    }

    public static Move chercherMeilleurCoupTemps(GameState state, long maxMs) {
        deadline   = System.currentTimeMillis() + maxMs;
        stopSearch = false;
        BitboardState bs    = state.getBitboardState();
        List<Move>    moves = MoveGenerator.generateLegalMoves(bs);
        if (moves.isEmpty()) throw new IllegalStateException("Aucun coup légal.");
        return new Recherche(MAX_PLY).iterativeDeepening(bs, moves);
    }

    /**
     * Vide la TT et le cache pions. À appeler entre deux parties ou avant les tests.
     */
    public static void clearTT() {
        TT.clear();
        PositionEvaluator.clearPawnCache();
    }

    /**
     * Vide uniquement le cache pions (utile pour les tests de cohérence).
     */
    public static void clearPawnTT() {
        PositionEvaluator.clearPawnCache();
    }

    /**
     * Configure les tablebases Syzygy à utiliser dans la recherche.
     * Les tablebases sont consultées au début de chaque nœud dont le
     * nombre de pièces est couvert.
     *
     * @param tb instance de tablebase (utilisez {@link SyzygyTablebase#disabled()} pour désactiver)
     */
    public static void setTablebase(SyzygyTablebase tb) {
        tablebase = (tb != null) ? tb : SyzygyTablebase.disabled();
    }

    /** Retourne l'instance de tablebase actuellement configurée. */
    public static SyzygyTablebase getTablebase() { return tablebase; }

    // =========================================================================
    // Normalisation des scores de mat
    // =========================================================================

    private static int scoreToTT(int score, int ply) {
        if (score >= MATE_THRESHOLD)  return score + ply;
        if (score <= -MATE_THRESHOLD) return score - ply;
        return score;
    }

    private static int scoreFromTT(int score, int ply) {
        if (score >= MATE_THRESHOLD)  return score - ply;
        if (score <= -MATE_THRESHOLD) return score + ply;
        return score;
    }

    // =========================================================================
    // SEE — Static Exchange Evaluation
    // =========================================================================

    /**
     * Score SEE pour le tri des captures et le filtre quiescence.
     * Positif = échange gagnant, négatif = échange perdant.
     */
    public static int seeCaptureScore(BitboardState state, Move move) {
        if (move.isEnPassant()) return 0;
        int toIdx = move.to().index;
        Color stm = state.getSideToMove();
        long occ  = state.getAllOccupancy();
        int victim   = capturedValueAt(state, toIdx, stm.opposite());
        if (victim == 0) return 0;
        int attacker = capturedValueAt(state, move.from().index, stm);
        long newOcc  = occ ^ (1L << move.from().index);
        return victim - seeInternal(state, toIdx, attacker, stm.opposite(), newOcc);
    }

    private static int seeInternal(BitboardState state, int toIdx, int lastValue,
                                    Color stm, long occ) {
        long lva = leastValuableAttacker(state, toIdx, stm, occ);
        if (lva == 0) return 0;
        int nextValue = lvaValue(state, lva);
        long newOcc   = occ ^ lva;
        return Math.max(0, lastValue - seeInternal(state, toIdx, nextValue, stm.opposite(), newOcc));
    }

    private static long leastValuableAttacker(BitboardState state, int toIdx,
                                               Color stm, long occ) {
        long pawnAtk = (stm == Color.WHITE)
            ? rules.AttackTables.BLACK_PAWN_ATTACKS[toIdx] & state.getBitboard(stm, Piece.PAWN)
            : rules.AttackTables.WHITE_PAWN_ATTACKS[toIdx] & state.getBitboard(stm, Piece.PAWN);
        if ((pawnAtk & occ) != 0) return Long.lowestOneBit(pawnAtk & occ);

        long kn = rules.AttackTables.KNIGHT_ATTACKS[toIdx] & state.getBitboard(stm, Piece.KNIGHT) & occ;
        if (kn != 0) return Long.lowestOneBit(kn);

        long bi = MagicBitboards.getBishopAttacks(toIdx, occ) & state.getBitboard(stm, Piece.BISHOP) & occ;
        if (bi != 0) return Long.lowestOneBit(bi);

        long rk = MagicBitboards.getRookAttacks(toIdx, occ) & state.getBitboard(stm, Piece.ROOK) & occ;
        if (rk != 0) return Long.lowestOneBit(rk);

        long qu = MagicBitboards.getQueenAttacks(toIdx, occ) & state.getBitboard(stm, Piece.QUEEN) & occ;
        if (qu != 0) return Long.lowestOneBit(qu);

        long kg = rules.AttackTables.KING_ATTACKS[toIdx] & state.getBitboard(stm, Piece.KING) & occ;
        if (kg != 0) return Long.lowestOneBit(kg);

        return 0L;
    }

    private static int lvaValue(BitboardState state, long lva) {
        if (lva == 0) return 0;
        for (Color c : Color.values())
            for (Piece p : Piece.values())
                if ((state.getBitboard(c, p) & lva) != 0) return p.value;
        return 0;
    }

    private static int capturedValueAt(BitboardState state, int sq, Color victim) {
        long mask = 1L << sq;
        for (Piece p : Piece.values())
            if ((state.getBitboard(victim, p) & mask) != 0) return p.value;
        return 0;
    }

    // =========================================================================
    // Contexte d'une session de recherche
    // =========================================================================

    private static final class Recherche {

        private final int     profondeurCible;
        private final int[][] killers     = new int[MAX_PLY][KILLERS_PER_PLY];
        private final int[][] history     = new int[64][64];
        private final int[][] counterMove = new int[64][64];
        private int           derniereScore = 0;

        Recherche(int profondeurCible) {
            this.profondeurCible = profondeurCible;
        }

        // ── Iterative Deepening + Aspiration Windows multi-étages ─────────────

        Move iterativeDeepening(BitboardState bs, List<Move> moves) {
            Move bestMove  = moves.get(0);
            int  prevScore = 0;

            for (int depth = 1; depth <= profondeurCible; depth++) {
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    stopSearch = true;
                    break;
                }

                Move    candidat;
                boolean nearMate = Math.abs(prevScore) >= MATE_THRESHOLD;

                if (depth <= 2 || nearMate) {
                    candidat = rechercherRacine(bs, moves, depth, -INF, INF);
                } else {
                    candidat = null;
                    int alphaBase = prevScore;
                    int betaBase  = prevScore;

                    for (int w = 0; w < ASPIRATION_WINDOWS.length; w++) {
                        if (stopSearch) break;
                        int delta = ASPIRATION_WINDOWS[w];
                        int alpha = (delta == Integer.MAX_VALUE) ? -INF : alphaBase - delta;
                        int beta  = (delta == Integer.MAX_VALUE) ?  INF : betaBase  + delta;

                        Move c = rechercherRacine(bs, moves, depth, alpha, beta);
                        if (stopSearch || c == null) break;
                        candidat = c;

                        if (derniereScore > alpha && derniereScore < beta) break;
                        if (derniereScore <= alpha) alphaBase = derniereScore;
                        if (derniereScore >= beta)  betaBase  = derniereScore;
                    }
                }

                if (stopSearch) break;
                if (candidat != null) {
                    bestMove  = candidat;
                    prevScore = derniereScore;
                }
            }
            return bestMove;
        }

        // ── Niveau racine ─────────────────────────────────────────────────────

        Move rechercherRacine(BitboardState bs, List<Move> allMoves,
                               int depth, int alpha, int beta) {
            Move       bestMove = null;
            List<Move> moves    = new ArrayList<>(allMoves);

            trierCoups(moves, bs, ttBestMove(bs.getZobristHash()), 0, 0);

            for (Move move : moves) {
                if (stopSearch || (deadline > 0 && System.currentTimeMillis() >= deadline)) {
                    stopSearch = true;
                    break;
                }
                BitboardState next  = MoveGenerator.applyMove(bs, move);
                Color         side  = bs.getSideToMove();
                int           score = -alphaBeta(next, depth - 1, -beta, -alpha,
                                                 side.opposite(), 1, false, 0,
                                                 move.from().index, move.to().index);
                if (score > alpha) {
                    alpha    = score;
                    bestMove = move;
                }
            }

            derniereScore = alpha;
            if (bestMove != null)
                TT.store(bs.getZobristHash(), scoreToTT(alpha, 0), depth,
                         TranspositionTable.EXACT, bestMove.getEncoded());
            return bestMove;
        }

        // ── Negamax Alpha-Bêta ────────────────────────────────────────────────

        int alphaBeta(BitboardState state, int depth, int alpha, int beta,
                      Color side, int ply, boolean nullOk, int extensions,
                      int lastMoveFrom, int lastMoveTo) {

            long hash = state.getZobristHash();
            if (ply >= MAX_PLY) return PositionEvaluator.evaluateFor(state, side);

            // ── Tablebases Syzygy ─────────────────────────────────────────────
            // Deux modes selon la disponibilité des fichiers :
            //
            // 1. Fichiers réels (.rtbw) disponibles → coupure immédiate avec score exact.
            //    La sonde DTZ garantit une progression vers le mat sans boucle.
            //
            // 2. Built-ins seuls (disabled / pas de fichiers) → on utilise le WDL
            //    uniquement comme borne inférieure alpha. On NE coupe PAS immédiatement
            //    car le score "mateScore - ply" est identique pour toutes les positions
            //    gagnantes (pas de DTZ), ce qui pousserait la TT à mémoriser des scores
            //    équivalents et l'IA à osciller en boucle.
            if (tablebase.canProbe(state)) {
                if (tablebase.isAvailable()) {
                    // Fichiers réels : coupure immédiate, sûr car le DTZ est connu
                    Integer tbScore = tablebase.probeScore(state, ply, INF);
                    if (tbScore != null) {
                        TT.store(hash, tbScore, depth, TranspositionTable.EXACT, 0);
                        return tbScore;
                    }
                } else {
                    // Built-ins seulement : on utilise le WDL uniquement comme
                    // borne de fenêtre pour empêcher l'alpha-bêta de considérer
                    // des coups qui mènent à une position perdante ou nulle.
                    // L'évaluateur de finale (PositionEvaluator.evaluateEndgame)
                    // fournit désormais le gradient positionnel nécessaire pour
                    // progresser vers le mat sans boucle.
                    WDL wdl = tablebase.probe(state);
                    if (wdl.isKnown()) {
                        if (wdl.isDraw()) {
                            // Nulle certaine : couper à 0.
                            return 0;
                        } else if (wdl.isWin()) {
                            // Victoire certaine : au moins mieux que 0.
                            // L'évaluateur de finale fournit le vrai gradient.
                            alpha = Math.max(alpha, 1);
                            if (alpha >= beta) return alpha;
                        } else if (wdl.isLoss()) {
                            // Défaite certaine : au pire -1.
                            beta = Math.min(beta, -1);
                            if (alpha >= beta) return beta;
                        }
                        // Continue : l'évaluateur normal prend le relais
                    }
                }
            }

            // ── TT ────────────────────────────────────────────────────────────
            TTEntry ttEntry       = TT.get(hash);
            int     ttMoveEncoded = 0;
            if (ttEntry != null) {
                ttMoveEncoded = ttEntry.bestMoveEncoded;
                if (ttEntry.depth >= depth) {
                    int ttScore = scoreFromTT(ttEntry.score, ply);
                    switch (ttEntry.type) {
                        case TranspositionTable.EXACT -> { return ttScore; }
                        case TranspositionTable.LOWER -> alpha = Math.max(alpha, ttScore);
                        case TranspositionTable.UPPER -> beta  = Math.min(beta,  ttScore);
                    }
                    if (alpha >= beta) return ttScore;
                }
            }

            // ── Feuille ───────────────────────────────────────────────────────
            if (depth == 0) return quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);

            boolean inCheck = MoveGenerator.isInCheck(state, side);

            // ── Extension d'échec ─────────────────────────────────────────────
            if (inCheck && extensions < MAX_EXTENSIONS
                    && depth <= profondeurCible * 2 && ply + depth < MAX_PLY) {
                depth++;
                extensions++;
            }

            // ── Prunings statiques (hors échec) ───────────────────────────────
            if (!inCheck) {
                int staticEval = PositionEvaluator.evaluateFor(state, side);

                // Razoring (depth=1)
                if (depth == 1 && staticEval + RAZOR_MARGIN < alpha) {
                    int q = quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);
                    if (q < alpha) return q;
                }

                // Futility Pruning (depth 1-2)
                if (depth <= FUTILITY_MAX_DEPTH && alpha < MATE_THRESHOLD && beta < MATE_THRESHOLD) {
                    if (staticEval + FUTILITY_MARGIN_PER_DEPTH * depth <= alpha)
                        return quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);
                }
            }

            // ── NMP adaptatif ─────────────────────────────────────────────────
            if (nullOk && !inCheck && extensions == 0
                    && depth >= NULL_MOVE_MIN_DEPTH
                    && materialCount(state, side) >= NULL_MOVE_MIN_MATERIAL) {
                int R = (depth >= NULL_MOVE_R_DEPTH_THR) ? NULL_MOVE_R_DEEP : NULL_MOVE_R_BASE;
                BitboardState nullState = state.withSideToMove(side.opposite());
                int ns = -alphaBeta(nullState, depth - 1 - R, -beta, -beta + 1,
                                    side.opposite(), ply + 1, false, extensions, 0, 0);
                if (ns >= beta) {
                    TT.store(hash, scoreToTT(beta, ply), depth, TranspositionTable.LOWER, 0);
                    return beta;
                }
            }

            // ── IID ───────────────────────────────────────────────────────────
            if (ttMoveEncoded == 0 && depth >= IID_MIN_DEPTH && !inCheck) {
                alphaBeta(state, depth - 2, alpha, beta, side, ply,
                          false, extensions, lastMoveFrom, lastMoveTo);
                TTEntry e = TT.get(hash);
                if (e != null) ttMoveEncoded = e.bestMoveEncoded;
            }

            // ── Génération des coups ──────────────────────────────────────────
            List<Move> moves = MoveGenerator.generateLegalMoves(state);
            if (moves.isEmpty()) return inCheck ? -INF + ply : 0;

            trierCoups(moves, state, ttMoveEncoded, ply, lastMoveFrom * 64 + lastMoveTo);

            // ── Singular Extensions setup ─────────────────────────────────────
            boolean singularSearch = depth >= SINGULAR_MIN_DEPTH
                    && ttMoveEncoded != 0 && ttEntry != null
                    && ttEntry.type != TranspositionTable.UPPER
                    && ttEntry.depth >= depth - 3 && !inCheck;

            byte nodeType        = TranspositionTable.UPPER;
            int  bestMoveEncoded = 0;
            int  bestScore       = -INF;
            int  movesExplored   = 0;
            int  quietsExplored  = 0;

            for (Move move : moves) {
                if (stopSearch || (deadline > 0 && (movesExplored & 63) == 0
                        && System.currentTimeMillis() >= deadline)) {
                    stopSearch = true;
                    break;
                }

                boolean isCapt = isCaptureFast(move, state);

                // ── LMP ───────────────────────────────────────────────────────
                if (!inCheck && !isCapt && !move.isPromotion()
                        && depth >= 1 && depth <= 4
                        && quietsExplored >= LMP_THRESHOLD[depth]
                        && bestScore > -MATE_THRESHOLD) {
                    movesExplored++;
                    continue;
                }

                // ── Singular Extension ────────────────────────────────────────
                int localExt = extensions;
                if (singularSearch && move.getEncoded() == ttMoveEncoded) {
                    int singularBeta  = scoreFromTT(ttEntry.score, ply) - SINGULAR_MARGIN;
                    int singularScore = alphaBetaExcluding(state, depth / 2,
                                                           singularBeta - 1, singularBeta,
                                                           side, ply + 1, ttMoveEncoded);
                    if (singularScore < singularBeta && localExt < MAX_EXTENSIONS) localExt++;
                }

                BitboardState next        = MoveGenerator.applyMove(state, move);
                int           lmrReduction = Math.min(3, 1 + depth / 6);
                boolean       canLmr       = !inCheck && !isCapt && !move.isPromotion()
                        && movesExplored >= LMR_FULL_DEPTH_MOVES
                        && depth >= LMR_MIN_DEPTH && lmrReduction < depth - 1;

                int score;
                if (movesExplored == 0) {
                    // PV node : recherche complète
                    score = -alphaBeta(next, depth - 1 + (localExt - extensions),
                                       -beta, -alpha, side.opposite(), ply + 1, true, localExt,
                                       move.from().index, move.to().index);
                } else if (canLmr) {
                    // LMR + PVS
                    score = -alphaBeta(next, depth - 1 - lmrReduction,
                                       -alpha - 1, -alpha, side.opposite(), ply + 1, true, localExt,
                                       move.from().index, move.to().index);
                    if (score > alpha) {
                        score = -alphaBeta(next, depth - 1,
                                           -alpha - 1, -alpha, side.opposite(), ply + 1, true, localExt,
                                           move.from().index, move.to().index);
                        if (score > alpha && score < beta)
                            score = -alphaBeta(next, depth - 1,
                                               -beta, -alpha, side.opposite(), ply + 1, true, localExt,
                                               move.from().index, move.to().index);
                    }
                } else {
                    // PVS strict : fenêtre nulle d'abord
                    score = -alphaBeta(next, depth - 1,
                                       -alpha - 1, -alpha, side.opposite(), ply + 1, true, localExt,
                                       move.from().index, move.to().index);
                    if (score > alpha && score < beta)
                        score = -alphaBeta(next, depth - 1,
                                           -beta, -alpha, side.opposite(), ply + 1, true, localExt,
                                           move.from().index, move.to().index);
                }

                movesExplored++;
                if (!isCapt && !move.isPromotion()) quietsExplored++;

                if (score > bestScore) { bestScore = score; bestMoveEncoded = move.getEncoded(); }

                if (score >= beta) {
                    if (!isCapt) {
                        enregistrerKiller(move.getEncoded(), ply);
                        if (lastMoveFrom < 64 && lastMoveTo < 64)
                            counterMove[lastMoveFrom][lastMoveTo] = move.getEncoded();
                        int bonus = depth * depth;
                        history[move.from().index][move.to().index] =
                            Math.min(32000, history[move.from().index][move.to().index] + bonus);
                        // History malus
                        int malus = depth * depth;
                        for (Move tried : moves) {
                            if (tried.getEncoded() == move.getEncoded()) break;
                            if (!isCaptureFast(tried, state) && !tried.isPromotion())
                                history[tried.from().index][tried.to().index] =
                                    Math.max(-32000,
                                        history[tried.from().index][tried.to().index] - malus);
                        }
                    }
                    TT.store(hash, scoreToTT(beta, ply), depth,
                             TranspositionTable.LOWER, move.getEncoded());
                    return beta;
                }
                if (score > alpha) { alpha = score; nodeType = TranspositionTable.EXACT; }
            }

            TT.store(hash, scoreToTT(alpha, ply), depth, nodeType, bestMoveEncoded);
            return alpha;
        }

        /** Recherche en excluant un coup (pour Singular Extensions). */
        private int alphaBetaExcluding(BitboardState state, int depth, int alpha, int beta,
                                        Color side, int ply, int excludedEncoded) {
            List<Move> moves = MoveGenerator.generateLegalMoves(state);
            int best = -INF;
            for (Move m : moves) {
                if (m.getEncoded() == excludedEncoded) continue;
                BitboardState next  = MoveGenerator.applyMove(state, m);
                int           score = -alphaBeta(next, depth - 1, -beta, -alpha,
                                                 side.opposite(), ply + 1, false, 0,
                                                 m.from().index, m.to().index);
                best  = Math.max(best, score);
                alpha = Math.max(alpha, score);
                if (alpha >= beta) return beta;
            }
            return best;
        }

        // ── Quiescence Search ─────────────────────────────────────────────────

        int quiescence(BitboardState state, int alpha, int beta,
                        Color side, int depthLeft) {
            int standPat = PositionEvaluator.evaluateFor(state, side);
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
            if (depthLeft == 0)   return alpha;

            List<Move> captures = MoveGenerator.generatePseudoLegalCaptures(state);
            if (captures.isEmpty()) return alpha;
            trierCapturesSEE(captures, state);

            for (Move capture : captures) {
                if (!capture.isPromotion()) {
                    int seeScore = seeCaptureScore(state, capture);
                    if (seeScore < 0) continue; // échange perdant → ignorer
                    int captureValue = capturedPieceValue(capture, state);
                    if (standPat + captureValue + DELTA_MARGIN <= alpha) continue;
                }
                BitboardState next = MoveGenerator.applyMove(state, capture);
                if (MoveGenerator.isInCheck(next, side)) continue;
                int score = -quiescence(next, -beta, -alpha, side.opposite(), depthLeft - 1);
                if (score >= beta) return beta;
                if (score > alpha) alpha = score;
            }
            return alpha;
        }

        // ── Killers ───────────────────────────────────────────────────────────

        private void enregistrerKiller(int encoded, int ply) {
            if (ply >= MAX_PLY || killers[ply][0] == encoded) return;
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = encoded;
        }

        private boolean estKiller(int encoded, int ply) {
            if (ply >= MAX_PLY) return false;
            return killers[ply][0] == encoded || killers[ply][1] == encoded;
        }

        // ── Move ordering ─────────────────────────────────────────────────────

        private void trierCoups(List<Move> moves, BitboardState state,
                                 int ttMoveEncoded, int ply, int lastMoveKey) {
            int n = moves.size(); int[] scores = new int[n];
            for (int i = 0; i < n; i++)
                scores[i] = scoreMove(moves.get(i), state, ttMoveEncoded, ply, lastMoveKey);
            for (int i = 1; i < n; i++) {
                Move m = moves.get(i); int s = scores[i]; int j = i - 1;
                while (j >= 0 && scores[j] < s) {
                    moves.set(j + 1, moves.get(j)); scores[j + 1] = scores[j]; j--;
                }
                moves.set(j + 1, m); scores[j + 1] = s;
            }
        }

        private int scoreMove(Move move, BitboardState state, int ttMoveEncoded,
                               int ply, int lastMoveKey) {
            int enc = move.getEncoded();
            if (enc == ttMoveEncoded && ttMoveEncoded != 0) return 2_000_000;
            if (move.isEnPassant()) return 1_000_000 + Piece.PAWN.value * 10 - Piece.PAWN.value;

            Color them = state.getSideToMove().opposite();
            int victimValue = pieceValueAt(state, them, move.to().mask);
            if (victimValue > 0) {
                int seeScore = seeCaptureScore(state, move);
                return seeScore >= 0 ? 1_500_000 + seeScore : 900_000 + seeScore;
            }

            if (move.isPromotion() && move.promotionPiece() == Piece.QUEEN) return 1_200_000;
            if (estKiller(enc, ply)) return 800_000;

            if (lastMoveKey > 0) {
                int lf = lastMoveKey / 64, lt = lastMoveKey % 64;
                if (lf < 64 && lt < 64 && counterMove[lf][lt] == enc) return COUNTERMOVE_SCORE;
            }

            return history[move.from().index][move.to().index];
        }

        private static int pieceValueAt(BitboardState state, Color color, long mask) {
            for (Piece p : Piece.values())
                if ((state.getBitboard(color, p) & mask) != 0) return p.value;
            return 0;
        }

        private void trierCapturesSEE(List<Move> captures, BitboardState state) {
            int n = captures.size(); int[] scores = new int[n];
            for (int i = 0; i < n; i++) {
                Move m = captures.get(i);
                scores[i] = m.isEnPassant() ? 0 : seeCaptureScore(state, m);
            }
            for (int i = 1; i < n; i++) {
                Move m = captures.get(i); int s = scores[i]; int j = i - 1;
                while (j >= 0 && scores[j] < s) {
                    captures.set(j + 1, captures.get(j)); scores[j + 1] = scores[j]; j--;
                }
                captures.set(j + 1, m); scores[j + 1] = s;
            }
        }
    }

    // =========================================================================
    // Utilitaires statiques
    // =========================================================================

    private static int ttBestMove(long hash) {
        TTEntry e = TT.get(hash);
        return (e != null) ? e.bestMoveEncoded : 0;
    }

    private static boolean isCaptureFast(Move move, BitboardState state) {
        if (move.isEnPassant() || move.isPromotion()) return true;
        return (state.getOccupancy(state.getSideToMove().opposite()) & move.to().mask) != 0;
    }

    private static int capturedPieceValue(Move move, BitboardState state) {
        if (move.isEnPassant()) return Piece.PAWN.value;
        Color them = state.getSideToMove().opposite();
        long toMask = move.to().mask;
        for (Piece p : Piece.values())
            if ((state.getBitboard(them, p) & toMask) != 0) return p.value;
        return 0;
    }

    private static int materialCount(BitboardState state, Color side) {
        return Long.bitCount(state.getBitboard(side, Piece.KNIGHT)) * Piece.KNIGHT.value
             + Long.bitCount(state.getBitboard(side, Piece.BISHOP)) * Piece.BISHOP.value
             + Long.bitCount(state.getBitboard(side, Piece.ROOK))   * Piece.ROOK.value
             + Long.bitCount(state.getBitboard(side, Piece.QUEEN))  * Piece.QUEEN.value;
    }
}
