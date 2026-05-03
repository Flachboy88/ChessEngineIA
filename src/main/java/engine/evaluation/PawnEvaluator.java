package engine.evaluation;

import core.Bitboard;
import model.Color;
import core.BitboardState;

/**
 * Évaluation de la structure de pions.
 *
 * <h2>Critères implémentés</h2>
 * <ul>
 *   <li><b>Pions doublés</b> — deux pions ou plus sur la même colonne.
 *       Malus par pion surnuméraire (le premier est "normal").</li>
 *   <li><b>Pions isolés</b> — aucun pion allié sur les colonnes adjacentes.
 *       Malus car ils ne peuvent être défendus que par des pièces, pas par d'autres pions.</li>
 *   <li><b>Pions passés</b> — aucun pion adverse devant eux sur leur colonne
 *       ni sur les colonnes adjacentes. Bonus croissant selon le rang d'avance.</li>
 * </ul>
 *
 * <h2>Implémentation bitboard</h2>
 * Toutes les opérations utilisent des masques bitboard pré-calculés — aucune boucle
 * sur les colonnes n'est nécessaire grâce aux opérations de décalage.
 * Le coût par appel est O(1) (quelques opérations bit).
 *
 * <h2>Interpolation MG/EG</h2>
 * Les malus et bonus dépendent de la phase de jeu :
 * <ul>
 *   <li>Pions doublés/isolés : légèrement plus pénalisants en finale (moins de pièces pour compenser)</li>
 *   <li>Pions passés : beaucoup plus précieux en finale (peuvent promouvoir)</li>
 * </ul>
 */
public final class PawnEvaluator {

    // ── Poids par phase ───────────────────────────────────────────────────────

    /** Malus pour un pion doublé (MG, EG). Plus sévère en finale. */
    private static final int DOUBLED_MG = -10;
    private static final int DOUBLED_EG = -20;

    /** Malus pour un pion isolé (MG, EG). */
    private static final int ISOLATED_MG = -12;
    private static final int ISOLATED_EG = -16;

    /**
     * Bonus pour un pion passé par rang d'avance (index 0=rang1, 6=rang7).
     * Le rang 0 et 7 ne sont jamais atteints (départ / promotion déjà jouée).
     * MG : faible (le pion passé ne vaut pas grand chose si on est en ouverture).
     * EG : très élevé aux rangs avancés — c'est la victoire.
     */
    private static final int[] PASSED_BONUS_MG = { 0,  5, 10, 15, 25, 40,  0, 0 };
    private static final int[] PASSED_BONUS_EG = { 0, 10, 20, 40, 70,120,  0, 0 };

    private PawnEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le score de structure de pions du point de vue des Blancs.
     * Score positif = avantage structural Blanc.
     *
     * @param state état courant
     * @param phase phase de jeu (1.0 = ouverture, 0.0 = finale)
     * @return score interpolé MG/EG
     */
    public static int evaluate(BitboardState state, int phase256) {
        return evaluateSide(state, Color.WHITE, phase256)
             - evaluateSide(state, Color.BLACK, phase256);
    }

    // ── Évaluation par camp ───────────────────────────────────────────────────

    private static int evaluateSide(BitboardState state, Color us, int phase256) {
        long ourPawns   = state.getBitboard(us, model.Piece.PAWN);
        long theirPawns = state.getBitboard(us.opposite(), model.Piece.PAWN);
        boolean white   = (us == Color.WHITE);

        int scoreMg = 0;
        int scoreEg = 0;

        // ── Pions doublés ────────────────────────────────────────────────────
        // Pour chaque colonne, compter les pions > 1 et appliquer un malus par surnuméraire
        for (int file = 0; file < 8; file++) {
            long fileMask = Bitboard.FILE_A << file;
            int count = Long.bitCount(ourPawns & fileMask);
            if (count > 1) {
                int doubles = count - 1;
                scoreMg += doubles * DOUBLED_MG;
                scoreEg += doubles * DOUBLED_EG;
            }
        }

        // ── Pions isolés et passés (boucle sur chaque pion) ──────────────────
        long temp = ourPawns;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;

            int file = sq % 8;
            int rank = sq / 8; // 0-7 (rang 1 = index 0)

            // Masque des colonnes adjacentes
            long adjFiles = adjacentFiles(file);

            // ── Pion isolé ───────────────────────────────────────────────────
            if ((ourPawns & adjFiles) == 0) {
                scoreMg += ISOLATED_MG;
                scoreEg += ISOLATED_EG;
            }

            // ── Pion passé ───────────────────────────────────────────────────
            // Un pion est passé si aucun pion ennemi ne le bloque sur sa colonne
            // ni sur les colonnes adjacentes, dans les rangs devant lui.
            long frontSpan = frontSpan(file, rank, white);
            if ((theirPawns & frontSpan) == 0) {
                // Rang d'avance du point de vue du propriétaire
                int advanceRank = white ? rank : (7 - rank);
                scoreMg += PASSED_BONUS_MG[advanceRank];
                scoreEg += PASSED_BONUS_EG[advanceRank];
            }
        }

        return interpolate(scoreMg, scoreEg, phase256);
    }

    // ── Helpers bitboard ──────────────────────────────────────────────────────

    /**
     * Retourne le masque des colonnes adjacentes à {@code file} (sans la colonne elle-même).
     */
    private static long adjacentFiles(int file) {
        long mask = 0L;
        if (file > 0) mask |= Bitboard.FILE_A << (file - 1);
        if (file < 7) mask |= Bitboard.FILE_A << (file + 1);
        return mask;
    }

    /**
     * Retourne le masque "devant" un pion : colonne + colonnes adjacentes,
     * rangs strictement devant le pion (du point de vue du propriétaire).
     *
     * @param file  colonne du pion (0-7)
     * @param rank  rang du pion (0-7)
     * @param white true si pion blanc
     */
    private static long frontSpan(int file, int rank, boolean white) {
        // Colonnes : celle du pion + adjacentes
        long cols = Bitboard.FILE_A << file;
        if (file > 0) cols |= Bitboard.FILE_A << (file - 1);
        if (file < 7) cols |= Bitboard.FILE_A << (file + 1);

        // Rangs devant le pion
        long front = 0L;
        if (white) {
            // Blancs avancent vers le rang 8 : rangs > rank
            for (int r = rank + 1; r <= 7; r++) {
                front |= Bitboard.RANK_1 << (r * 8);
            }
        } else {
            // Noirs avancent vers le rang 1 : rangs < rank
            for (int r = rank - 1; r >= 0; r--) {
                front |= Bitboard.RANK_1 << (r * 8);
            }
        }
        return cols & front;
    }

    /** Interpolation linéaire entre score MG et EG selon la phase (0–256). */
    public static int interpolate(int mg, int eg, int phase256) {
        return (mg * phase256 + eg * (256 - phase256)) >> 8;
    }
}
