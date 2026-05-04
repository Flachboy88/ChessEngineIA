package engine.search;

import core.BitboardState;
import engine.evaluation.PositionEvaluator;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
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
 *   <li><b>Aspiration Windows</b> — fenêtre ±{@value ASPIRATION_WINDOW} cp autour du score précédent,
 *       désactivée automatiquement si le score précédent est proche du mat</li>
 *   <li><b>Table de transposition (TT)</b> — {@link TTEntry} EXACT/LOWER/UPPER,
 *       scores de mat normalisés par ply (stockage ply-indépendant)</li>
 *   <li><b>Null Move Pruning (NMP)</b> — R={@value NULL_MOVE_R}, désactivé en échec/finale</li>
 *   <li><b>Extension d'échec</b> — +1 ply si le roi est en échec, bornée à {@value MAX_EXTENSIONS} extensions max</li>
 *   <li><b>Late Move Reduction (LMR)</b> — réduit les coups tardifs silencieux, réduction variable selon depth/movesCount</li>
 *   <li><b>Futility Pruning</b> — élagage en nœuds proches des feuilles (depth 1-2)</li>
 *   <li><b>Move ordering</b> — TT > MVV-LVA > promotions > killers > history heuristic</li>
 *   <li><b>Killer moves</b> — 2 par ply</li>
 *   <li><b>History Heuristic</b> — score cumulatif des coups ayant causé des bêta-coupures</li>
 *   <li><b>Quiescence search + Delta Pruning</b></li>
 * </ol>
 */
public final class AlphaBetaSearch {

    static final int INF = PositionEvaluator.MATE_SCORE;

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final int MAX_QUIESCENCE_DEPTH = 6;
    private static final int KILLERS_PER_PLY      = 2;
    private static final int MAX_PLY              = 64;

    // Null Move Pruning
    private static final int NULL_MOVE_R            = 3;
    private static final int NULL_MOVE_MIN_DEPTH    = 3;
    // Doit représenter au moins une pièce lourde (tour=477) pour éviter NMP en finale
    private static final int NULL_MOVE_MIN_MATERIAL = 1500;

    // Late Move Reduction — réduction variable : 1 + depth/6 (plafonné à 3)
    private static final int LMR_FULL_DEPTH_MOVES = 4;
    private static final int LMR_MIN_DEPTH        = 3;

    // Futility Pruning — marge par profondeur (centipions)
    // depth=1 : 150cp, depth=2 : 300cp
    private static final int FUTILITY_MARGIN_PER_DEPTH = 150;
    private static final int FUTILITY_MAX_DEPTH        = 2;

    // Aspiration Windows
    private static final int ASPIRATION_WINDOW = 50;

    /**
     * Seuil en-dessous duquel un score est considéré comme "proche du mat".
     */
    private static final int MATE_THRESHOLD = INF - MAX_PLY * 2;

    // Delta Pruning (quiescence)
    private static final int DELTA_MARGIN = 200;

    /**
     * Nombre maximal d'extensions d'échec accumulées sur un chemin.
     */
    private static final int MAX_EXTENSIONS = 3;

    // ── Table de transposition (statique, partagée entre les coups) ───────────
    private static final TranspositionTable TT = new TranspositionTable();

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

    public static void clearTT() { TT.clear(); }

    // =========================================================================
    // Normalisation des scores de mat pour la TT
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
    // Contexte d'une session de recherche
    // =========================================================================

    private static final class Recherche {

        private final int     profondeurCible;
        private final int[][] killers = new int[MAX_PLY][KILLERS_PER_PLY];
        /**
         * History Heuristic : history[from][to] += depth² quand un coup silencieux
         * cause une bêta-coupure. Réinitialisé à chaque nouvelle recherche IDDFS.
         * Favorise les coups qui ont été "bons" dans le passé de la recherche.
         */
        private final int[][] history = new int[64][64];
        private int           derniereScore = 0;

        Recherche(int profondeurCible) {
            this.profondeurCible = profondeurCible;
        }

        // ── Iterative Deepening + Aspiration Windows ──────────────────────────

        Move iterativeDeepening(BitboardState bs, List<Move> moves) {
            Move bestMove  = moves.get(0);
            int  prevScore = 0;

            for (int depth = 1; depth <= profondeurCible; depth++) {
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    stopSearch = true;
                    break;
                }

                Move candidat;
                boolean nearMate = Math.abs(prevScore) >= MATE_THRESHOLD;

                if (depth <= 2 || nearMate) {
                    candidat = rechercherRacine(bs, moves, depth, -INF, INF);
                } else {
                    int alpha = prevScore - ASPIRATION_WINDOW;
                    int beta  = prevScore + ASPIRATION_WINDOW;

                    candidat = rechercherRacine(bs, moves, depth, alpha, beta);

                    if (!stopSearch && (candidat == null || derniereScore <= alpha)) {
                        candidat = rechercherRacine(bs, moves, depth, -INF, beta);
                    } else if (!stopSearch && derniereScore >= beta) {
                        candidat = rechercherRacine(bs, moves, depth, alpha, INF);
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
            Color      side     = bs.getSideToMove();
            Move       bestMove = null;
            List<Move> moves    = new ArrayList<>(allMoves);

            trierCoups(moves, bs, ttBestMove(bs.getZobristHash()), 0);

            for (Move move : moves) {
                if (stopSearch || (deadline > 0 && System.currentTimeMillis() >= deadline)) {
                    stopSearch = true;
                    break;
                }
                BitboardState next  = MoveGenerator.applyMove(bs, move);
                int           score = -alphaBeta(next, depth - 1, -beta, -alpha,
                                                 side.opposite(), 1, false, 0);
                if (score > alpha) {
                    alpha    = score;
                    bestMove = move;
                }
            }

            derniereScore = alpha;
            if (bestMove != null) {
                TT.store(bs.getZobristHash(), scoreToTT(alpha, 0), depth,
                         TranspositionTable.EXACT, bestMove.getEncoded());
            }
            return bestMove;
        }

        // ── Negamax Alpha-Bêta ────────────────────────────────────────────────

        int alphaBeta(BitboardState state, int depth, int alpha, int beta,
                      Color side, int ply, boolean nullOk, int extensions) {

            long hash = state.getZobristHash();

            if (ply >= MAX_PLY) {
                return PositionEvaluator.evaluateFor(state, side);
            }

            // ── Consultation TT ───────────────────────────────────────────────
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

            // ── Feuille → quiescence ──────────────────────────────────────────
            if (depth == 0) {
                return quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);
            }

            boolean inCheck = MoveGenerator.isInCheck(state, side);

            // ── Extension d'échec ──────────────────────────────────────────────
            if (inCheck && extensions < MAX_EXTENSIONS
                    && depth <= profondeurCible * 2
                    && ply + depth < MAX_PLY) {
                depth++;
                extensions++;
            }

            // ── Futility Pruning (depth 1-2, hors échec) ─────────────────────
            // Si le score statique + une marge est encore en-dessous d'alpha,
            // les coups silencieux ne peuvent pas améliorer la position → on les coupe.
            if (!inCheck && depth <= FUTILITY_MAX_DEPTH && alpha < MATE_THRESHOLD && beta < MATE_THRESHOLD) {
                int staticEval = PositionEvaluator.evaluateFor(state, side);
                int margin     = FUTILITY_MARGIN_PER_DEPTH * depth;
                if (staticEval + margin <= alpha) {
                    // On entre quand même en quiescence pour ne pas rater des captures importantes
                    return quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);
                }
            }

            // ── Null Move Pruning ─────────────────────────────────────────────
            if (nullOk
                    && !inCheck
                    && extensions == 0
                    && depth >= NULL_MOVE_MIN_DEPTH
                    && materialCount(state, side) >= NULL_MOVE_MIN_MATERIAL) {

                BitboardState nullState = state.withSideToMove(side.opposite());
                int nullScore = -alphaBeta(nullState,
                                           depth - 1 - NULL_MOVE_R,
                                           -beta, -beta + 1,
                                           side.opposite(), ply + 1,
                                           false, extensions);
                if (nullScore >= beta) {
                    TT.store(hash, scoreToTT(beta, ply), depth,
                             TranspositionTable.LOWER, 0);
                    return beta;
                }
            }

            // ── Génération des coups ──────────────────────────────────────────
            List<Move> moves = MoveGenerator.generateLegalMoves(state);
            if (moves.isEmpty()) {
                return inCheck ? -INF + ply : 0;
            }

            trierCoups(moves, state, ttMoveEncoded, ply);

            // ── Exploration avec LMR variable ─────────────────────────────────
            byte nodeType        = TranspositionTable.UPPER;
            int  bestMoveEncoded = 0;
            int  bestScore       = -INF;
            int  movesExplored   = 0;

            for (Move move : moves) {
                if (stopSearch || (deadline > 0 && (movesExplored & 63) == 0
                        && System.currentTimeMillis() >= deadline)) {
                    stopSearch = true;
                    break;
                }

                BitboardState next   = MoveGenerator.applyMove(state, move);
                boolean       isCapt = isCaptureFast(move, state);
                int           score;

                // LMR variable : réduction = 1 + depth/6, plafonnée à 3
                // Plus agressif en profondeur élevée où les coups tardifs sont vraiment mauvais
                int lmrReduction = Math.min(3, 1 + depth / 6);

                boolean canLmr = !inCheck
                        && !isCapt
                        && !move.isPromotion()
                        && movesExplored >= LMR_FULL_DEPTH_MOVES
                        && depth >= LMR_MIN_DEPTH
                        && lmrReduction < depth - 1;

                if (canLmr) {
                    // Recherche réduite en fenêtre nulle
                    score = -alphaBeta(next, depth - 1 - lmrReduction,
                                       -alpha - 1, -alpha,
                                       side.opposite(), ply + 1, true, extensions);
                    // Re-recherche complète si le coup semble meilleur qu'attendu
                    if (score > alpha) {
                        score = -alphaBeta(next, depth - 1,
                                           -beta, -alpha,
                                           side.opposite(), ply + 1, true, extensions);
                    }
                } else {
                    score = -alphaBeta(next, depth - 1,
                                       -beta, -alpha,
                                       side.opposite(), ply + 1, true, extensions);
                }

                movesExplored++;

                if (score > bestScore) {
                    bestScore       = score;
                    bestMoveEncoded = move.getEncoded();
                }
                if (score >= beta) {
                    if (!isCapt) {
                        enregistrerKiller(move.getEncoded(), ply);
                        // History Heuristic : bonus += depth² pour ce coup
                        int bonus = depth * depth;
                        history[move.from().index][move.to().index] =
                            Math.min(32000, history[move.from().index][move.to().index] + bonus);
                    }
                    TT.store(hash, scoreToTT(beta, ply), depth,
                             TranspositionTable.LOWER, move.getEncoded());
                    return beta;
                }
                if (score > alpha) {
                    alpha    = score;
                    nodeType = TranspositionTable.EXACT;
                }
            }

            TT.store(hash, scoreToTT(alpha, ply), depth, nodeType, bestMoveEncoded);
            return alpha;
        }

        // ── Quiescence Search + Delta Pruning ─────────────────────────────────

        int quiescence(BitboardState state, int alpha, int beta,
                        Color side, int depthLeft) {

            int standPat = PositionEvaluator.evaluateFor(state, side);
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
            if (depthLeft == 0)   return alpha;

            List<Move> captures = MoveGenerator.generatePseudoLegalCaptures(state);
            if (captures.isEmpty()) return alpha;
            trierCapturesMvvLva(captures, state);

            for (Move capture : captures) {
                if (!capture.isPromotion()) {
                    int captureValue = capturedPieceValue(capture, state);
                    if (standPat + captureValue + DELTA_MARGIN <= alpha) continue;
                }

                BitboardState next = MoveGenerator.applyMove(state, capture);
                if (MoveGenerator.isInCheck(next, side)) continue;

                int score = -quiescence(next, -beta, -alpha,
                                        side.opposite(), depthLeft - 1);
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
                                 int ttMoveEncoded, int ply) {
            int   n      = moves.size();
            int[] scores = new int[n];
            for (int i = 0; i < n; i++)
                scores[i] = scoreMove(moves.get(i), state, ttMoveEncoded, ply);

            // Insertion sort (efficace pour n < 50)
            for (int i = 1; i < n; i++) {
                Move m = moves.get(i); int s = scores[i]; int j = i - 1;
                while (j >= 0 && scores[j] < s) {
                    moves.set(j + 1, moves.get(j));
                    scores[j + 1] = scores[j];
                    j--;
                }
                moves.set(j + 1, m);
                scores[j + 1] = s;
            }
        }

        private int scoreMove(Move move, BitboardState state, int ttMoveEncoded, int ply) {
            int enc = move.getEncoded();
            if (enc == ttMoveEncoded && ttMoveEncoded != 0) return 2_000_000;

            if (move.isEnPassant())
                return 1_000_000 + Piece.PAWN.value * 10 - Piece.PAWN.value;

            long toMask = move.to().mask;
            Color them = state.getSideToMove().opposite();
            int victimValue = pieceValueAt(state, them, toMask);
            if (victimValue > 0) {
                int attValue = pieceValueAt(state, state.getSideToMove(), move.from().mask);
                return 1_000_000 + victimValue * 10 - attValue;
            }
            if (move.isPromotion() && move.promotionPiece() == Piece.QUEEN) return 900_000;
            if (estKiller(enc, ply)) return 800_000;
            // History heuristic : [0, 32000] → normalisé pour rester sous les killers
            return history[move.from().index][move.to().index];
        }

        private static int pieceValueAt(BitboardState state, Color color, long mask) {
            for (Piece p : Piece.values()) {
                if ((state.getBitboard(color, p) & mask) != 0) return p.value;
            }
            return 0;
        }

        private void trierCapturesMvvLva(List<Move> captures, BitboardState state) {
            int   n      = captures.size();
            int[] scores = new int[n];
            Color them = state.getSideToMove().opposite();
            for (int i = 0; i < n; i++) {
                Move m = captures.get(i);
                if (m.isEnPassant()) { scores[i] = Piece.PAWN.value * 10 - Piece.PAWN.value; continue; }
                int v = pieceValueAt(state, them, m.to().mask);
                int a = pieceValueAt(state, state.getSideToMove(), m.from().mask);
                scores[i] = v * 10 - a;
            }
            for (int i = 1; i < n; i++) {
                Move m = captures.get(i); int s = scores[i]; int j = i - 1;
                while (j >= 0 && scores[j] < s) {
                    captures.set(j + 1, captures.get(j));
                    scores[j + 1] = scores[j]; j--;
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
        long toMask = move.to().mask;
        return (state.getOccupancy(state.getSideToMove().opposite()) & toMask) != 0;
    }

    private static boolean isTrueCapture(Move move, BitboardState state) {
        return move.isEnPassant() || state.getPieceAt(move.to()) != null;
    }

    private static boolean isCapture(Move move, BitboardState state) {
        return move.isEnPassant()
            || move.isPromotion()
            || state.getPieceAt(move.to()) != null;
    }

    private static int capturedPieceValue(Move move, BitboardState state) {
        if (move.isEnPassant()) return Piece.PAWN.value;
        long toMask = move.to().mask;
        Color them = state.getSideToMove().opposite();
        for (Piece p : Piece.values()) {
            if ((state.getBitboard(them, p) & toMask) != 0) return p.value;
        }
        return 0;
    }

    private static int materialCount(BitboardState state, Color side) {
        return Long.bitCount(state.getBitboard(side, Piece.KNIGHT)) * Piece.KNIGHT.value
             + Long.bitCount(state.getBitboard(side, Piece.BISHOP)) * Piece.BISHOP.value
             + Long.bitCount(state.getBitboard(side, Piece.ROOK))   * Piece.ROOK.value
             + Long.bitCount(state.getBitboard(side, Piece.QUEEN))  * Piece.QUEEN.value;
    }
}
