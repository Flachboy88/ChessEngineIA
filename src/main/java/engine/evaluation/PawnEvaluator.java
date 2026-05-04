package engine.evaluation;

import core.Bitboard;
import model.Color;
import core.BitboardState;

/**
 * Évaluation de la structure de pions.
 *
 * <h2>Critères implémentés</h2>
 * <ul>
 *   <li><b>Pions doublés</b> — malus par pion surnuméraire sur la même colonne.</li>
 *   <li><b>Pions isolés</b> — aucun pion allié sur les colonnes adjacentes.</li>
 *   <li><b>Pions passés</b> — bonus croissant avec le rang, majoré si la route est libre
 *       (aucune pièce ennemie sur la colonne devant lui).</li>
 *   <li><b>Pions arrière</b> — pion qui ne peut être soutenu par aucun pion allié
 *       et est sur une colonne semi-ouverte (vulnérable aux tours ennemies).</li>
 *   <li><b>Pions connectés</b> — petit bonus pour les pions qui se soutiennent mutuellement.</li>
 * </ul>
 *
 * <h2>Interpolation MG/EG</h2>
 * Les malus et bonus dépendent de la phase de jeu.
 */
public final class PawnEvaluator {

    // ── Poids par phase ───────────────────────────────────────────────────────

    private static final int DOUBLED_MG  = -10;
    private static final int DOUBLED_EG  = -25;

    private static final int ISOLATED_MG = -15;
    private static final int ISOLATED_EG = -20;

    private static final int BACKWARD_MG = -10;
    private static final int BACKWARD_EG = -15;

    private static final int CONNECTED_MG =  5;
    private static final int CONNECTED_EG =  8;

    /**
     * Bonus pour un pion passé par rang d'avance (index 0=rang1, 7=rang8).
     * Le rang 0 et 7 ne sont jamais atteints (départ / promotion déjà jouée).
     */
    private static final int[] PASSED_BONUS_MG = { 0,  5, 12, 20, 35, 55, 80, 0 };
    private static final int[] PASSED_BONUS_EG = { 0, 15, 30, 55, 90,140,200, 0 };

    /**
     * Bonus supplémentaire si la case devant le pion passé est libre (route ouverte).
     * Encourage le moteur à pousser le pion.
     */
    private static final int PASSED_FREE_ADVANCE_MG = 10;
    private static final int PASSED_FREE_ADVANCE_EG = 25;

    private PawnEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    public static int evaluate(BitboardState state, int phase256) {
        return evaluateSide(state, Color.WHITE, phase256)
             - evaluateSide(state, Color.BLACK, phase256);
    }

    // ── Évaluation par camp ───────────────────────────────────────────────────

    private static int evaluateSide(BitboardState state, Color us, int phase256) {
        long ourPawns   = state.getBitboard(us, model.Piece.PAWN);
        long theirPawns = state.getBitboard(us.opposite(), model.Piece.PAWN);
        long allOcc     = state.getAllOccupancy();
        boolean white   = (us == Color.WHITE);

        int scoreMg = 0;
        int scoreEg = 0;

        // ── Pions doublés ────────────────────────────────────────────────────
        for (int file = 0; file < 8; file++) {
            long fileMask = Bitboard.FILE_A << file;
            int count = Long.bitCount(ourPawns & fileMask);
            if (count > 1) {
                int doubles = count - 1;
                scoreMg += doubles * DOUBLED_MG;
                scoreEg += doubles * DOUBLED_EG;
            }
        }

        // ── Boucle par pion ───────────────────────────────────────────────────
        long temp = ourPawns;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;

            int file = sq % 8;
            int rank = sq / 8;

            long adjFiles   = adjacentFiles(file);
            long colMask    = Bitboard.FILE_A << file;
            long frontSpan  = frontSpan(file, rank, white);

            // ── Pion isolé ───────────────────────────────────────────────────
            boolean isolated = (ourPawns & adjFiles) == 0;
            if (isolated) {
                scoreMg += ISOLATED_MG;
                scoreEg += ISOLATED_EG;
            }

            // ── Pion connecté (soutenu latéralement ou en diagonale) ─────────
            // Un pion est "connecté" si un pion allié est sur une case adjacente
            // ou sur la diagonale arrière (le soutient potentiellement).
            long pawnMask  = 1L << sq;
            long adjacentMask = 0L;
            if (file > 0) adjacentMask |= pawnMask >>> 1; // même rang, colonne gauche
            if (file < 7) adjacentMask |= pawnMask << 1;  // même rang, colonne droite
            // Diagonales arrière
            if (white && rank > 0) {
                if (file > 0) adjacentMask |= pawnMask >>> 9;
                if (file < 7) adjacentMask |= pawnMask >>> 7;
            } else if (!white && rank < 7) {
                if (file > 0) adjacentMask |= pawnMask << 7;
                if (file < 7) adjacentMask |= pawnMask << 9;
            }
            if ((ourPawns & adjacentMask) != 0) {
                scoreMg += CONNECTED_MG;
                scoreEg += CONNECTED_EG;
            }

            // ── Pion passé ───────────────────────────────────────────────────
            if ((theirPawns & frontSpan) == 0) {
                int advanceRank = white ? rank : (7 - rank);
                scoreMg += PASSED_BONUS_MG[advanceRank];
                scoreEg += PASSED_BONUS_EG[advanceRank];

                // Bonus si la case immédiatement devant est libre (libre d'avancer)
                int nextSq  = white ? sq + 8 : sq - 8;
                if (nextSq >= 0 && nextSq < 64) {
                    long nextMask = 1L << nextSq;
                    if ((allOcc & nextMask) == 0) {
                        scoreMg += PASSED_FREE_ADVANCE_MG;
                        scoreEg += PASSED_FREE_ADVANCE_EG;
                    }
                }
            }

            // ── Pion arrière (backward pawn) ─────────────────────────────────
            // Un pion est "arrière" si :
            //   1. Aucun pion allié ne peut le soutenir depuis derrière (sur adj files)
            //   2. Il est sur une colonne semi-ouverte (ennemi peut pousser dessus)
            //   3. Il n'est pas déjà isolé (pénalité cumulée excessive)
            if (!isolated) {
                // Cases "derrière" le pion sur les colonnes adjacentes
                long rearSpan = rearSpan(file, rank, white);
                boolean noSupport = (ourPawns & rearSpan) == 0;
                boolean semiOpen  = (theirPawns & colMask) != 0; // pion ennemi devant (col ouverte pour eux)
                if (noSupport && semiOpen) {
                    scoreMg += BACKWARD_MG;
                    scoreEg += BACKWARD_EG;
                }
            }
        }

        return interpolate(scoreMg, scoreEg, phase256);
    }

    // ── Helpers bitboard ──────────────────────────────────────────────────────

    private static long adjacentFiles(int file) {
        long mask = 0L;
        if (file > 0) mask |= Bitboard.FILE_A << (file - 1);
        if (file < 7) mask |= Bitboard.FILE_A << (file + 1);
        return mask;
    }

    /**
     * Masque "devant" un pion : col + colonnes adjacentes, rangs strictement devant.
     */
    private static long frontSpan(int file, int rank, boolean white) {
        long cols = Bitboard.FILE_A << file;
        if (file > 0) cols |= Bitboard.FILE_A << (file - 1);
        if (file < 7) cols |= Bitboard.FILE_A << (file + 1);

        long front = 0L;
        if (white) {
            for (int r = rank + 1; r <= 7; r++) front |= Bitboard.RANK_1 << (r * 8);
        } else {
            for (int r = rank - 1; r >= 0; r--) front |= Bitboard.RANK_1 << (r * 8);
        }
        return cols & front;
    }

    /**
     * Masque "derrière" un pion sur les colonnes adjacentes (pour détecter support potentiel).
     */
    private static long rearSpan(int file, int rank, boolean white) {
        long cols = adjacentFiles(file);
        long rear = 0L;
        if (white) {
            // Derrière = rangs inférieurs
            for (int r = 0; r < rank; r++) rear |= Bitboard.RANK_1 << (r * 8);
        } else {
            // Derrière = rangs supérieurs
            for (int r = rank + 1; r <= 7; r++) rear |= Bitboard.RANK_1 << (r * 8);
        }
        return cols & rear;
    }

    /** Interpolation linéaire entre score MG et EG selon la phase (0–256). */
    public static int interpolate(int mg, int eg, int phase256) {
        return (mg * phase256 + eg * (256 - phase256)) >> 8;
    }
}
