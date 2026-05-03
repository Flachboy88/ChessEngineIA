package engine.evaluation;

import core.Bitboard;
import core.BitboardState;
import model.Color;
import model.Piece;
import rules.AttackTables;

/**
 * Évaluation de la sécurité du roi.
 *
 * <h2>Critères implémentés</h2>
 * <ul>
 *   <li><b>Bouclier de pions</b> — bonus pour les pions devant le roi roqé.
 *       Malus si un pion bouclier est absent ou avancé (trou dans le bouclier).</li>
 *   <li><b>Cases voisines attaquées</b> — malus pour chaque case autour du roi
 *       attaquée par une pièce ennemie (hors pions).</li>
 *   <li><b>Colonnes ouvertes proches du roi</b> — malus si une colonne ouverte
 *       ou semi-ouverte se trouve sur la colonne du roi ou adjacente (tour/dame ennemies
 *       ont accès direct).</li>
 * </ul>
 *
 * <h2>Pondération selon la phase</h2>
 * La sécurité du roi est <b>prépondérante en milieu de jeu</b> (quand les pièces
 * lourdes ennemies peuvent attaquer) et négligeable en finale (les rois peuvent
 * sortir au centre). On multiplie donc par la phase de jeu.
 *
 * <h2>Performance</h2>
 * Tout est bitboard — aucun `generateLegalMoves`. Le coût est O(1).
 */
public final class KingSafety {

    // ── Poids ─────────────────────────────────────────────────────────────────

    /** Bonus pour un pion bouclier au rang immédiat devant le roi (g2/h2 côté roqé). */
    private static final int PAWN_SHIELD_CLOSE  =  8;
    /** Bonus pour un pion bouclier au 2e rang devant le roi (g3/h3). */
    private static final int PAWN_SHIELD_FAR    =  4;
    /** Malus si un pion bouclier est complètement absent. */
    private static final int PAWN_SHIELD_MISS   = -15;

    /** Malus par case voisine du roi attaquée par une pièce ennemie (hors pions). */
    private static final int KING_ATTACK_UNIT   = -4;

    /** Malus par colonne ouverte ou semi-ouverte adjacent au roi (tour/dame adverses). */
    private static final int OPEN_FILE_NEAR_KING = -10;

    private KingSafety() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le score de sécurité du roi du point de vue des Blancs.
     * Score positif = les Blancs sont plus en sécurité.
     * Multiplié par la phase (évaluation MG principalement).
     *
     * @param state état courant
     * @param phase phase de jeu (1.0 = ouverture, 0.0 = finale)
     * @return score de sécurité pondéré
     */
    public static int evaluate(BitboardState state, double phase) {
        // La sécurité du roi est essentiellement un concept de milieu de jeu.
        // En finale (phase→0), on réduit fortement son poids.
        int whiteScore = evaluateSide(state, Color.WHITE);
        int blackScore = evaluateSide(state, Color.BLACK);
        return (int) ((whiteScore - blackScore) * phase);
    }

    // ── Évaluation par camp ───────────────────────────────────────────────────

    private static int evaluateSide(BitboardState state, Color us) {
        long kingBB = state.getBitboard(us, Piece.KING);
        if (kingBB == 0) return 0;

        int kingSq  = Long.numberOfTrailingZeros(kingBB);
        int kingFile = kingSq % 8;
        int kingRank = kingSq / 8;
        boolean white = (us == Color.WHITE);

        long ourPawns = state.getBitboard(us, Piece.PAWN);
        long allPawns = ourPawns | state.getBitboard(us.opposite(), Piece.PAWN);
        long occ      = state.getAllOccupancy();

        int score = 0;

        // ── 1. Bouclier de pions ─────────────────────────────────────────────
        // Seulement pertinent si le roi est sur les ailes (colonnes a-c ou f-h)
        // et qu'il n'est pas au centre (où le bouclier serait illusoire).
        if (kingFile <= 2 || kingFile >= 5) {
            score += pawnShieldScore(kingSq, kingFile, ourPawns, white);
        }

        // ── 2. Cases voisines attaquées ──────────────────────────────────────
        long kingZone = AttackTables.KING_ATTACKS[kingSq] | kingBB;
        Color them    = us.opposite();
        long enemyPieces = state.getOccupancy(them);

        // Cavaliers ennemis attaquant la zone du roi
        long enemyKnights = state.getBitboard(them, Piece.KNIGHT);
        long temp = enemyKnights;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            if ((AttackTables.KNIGHT_ATTACKS[sq] & kingZone) != 0) {
                score += KING_ATTACK_UNIT;
            }
        }

        // Fous + Dames ennemis attaquant la zone du roi (diagonales)
        long enemyBishopQueens = state.getBitboard(them, Piece.BISHOP)
                               | state.getBitboard(them, Piece.QUEEN);
        temp = enemyBishopQueens;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = bishopAttacks(sq, occ);
            if ((attacks & kingZone) != 0) {
                score += KING_ATTACK_UNIT * 2; // fou/dame = plus dangereux
            }
        }

        // Tours + Dames ennemies attaquant la zone du roi (lignes)
        long enemyRookQueens = state.getBitboard(them, Piece.ROOK)
                             | state.getBitboard(them, Piece.QUEEN);
        temp = enemyRookQueens;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = rookAttacks(sq, occ);
            if ((attacks & kingZone) != 0) {
                score += KING_ATTACK_UNIT * 2;
            }
        }

        // ── 3. Colonnes ouvertes / semi-ouvertes près du roi ─────────────────
        // On inspecte les 3 colonnes autour du roi (ou moins sur les bords)
        int fileMin = Math.max(0, kingFile - 1);
        int fileMax = Math.min(7, kingFile + 1);
        for (int f = fileMin; f <= fileMax; f++) {
            long col = Bitboard.FILE_A << f;
            boolean openForUs    = (ourPawns  & col) == 0;
            boolean openForThem  = (state.getBitboard(them, Piece.PAWN) & col) == 0;
            if (openForUs && openForThem) {
                // Colonne complètement ouverte : très dangereux
                score += OPEN_FILE_NEAR_KING * 2;
            } else if (openForUs) {
                // Semi-ouverte pour nous : pas de pions blancs → tour/dame peut s'infiltrer
                score += OPEN_FILE_NEAR_KING;
            }
        }

        return score;
    }

    // ── Bouclier de pions ─────────────────────────────────────────────────────

    /**
     * Score du bouclier de pions pour un roi à {@code kingSq}.
     * On regarde les 3 colonnes devant lui (±1 colonne) sur 2 rangs.
     */
    private static int pawnShieldScore(int kingSq, int kingFile,
                                       long ourPawns, boolean white) {
        int score = 0;
        int fileMin = Math.max(0, kingFile - 1);
        int fileMax = Math.min(7, kingFile + 1);

        for (int f = fileMin; f <= fileMax; f++) {
            long col = Bitboard.FILE_A << f;

            // Rang immédiat devant le roi (rang+1 pour blancs, rang-1 pour noirs)
            long closeRank = white
                ? (Bitboard.RANK_1 << ((kingSq / 8 + 1) * 8))
                : (Bitboard.RANK_1 << ((kingSq / 8 - 1) * 8));
            // Rang 2 devant
            long farRank = white
                ? (Bitboard.RANK_1 << ((kingSq / 8 + 2) * 8))
                : (Bitboard.RANK_1 << ((kingSq / 8 - 2) * 8));

            if ((ourPawns & col & closeRank) != 0) {
                score += PAWN_SHIELD_CLOSE;
            } else if ((ourPawns & col & farRank) != 0) {
                score += PAWN_SHIELD_FAR;
            } else {
                // Pion bouclier absent
                score += PAWN_SHIELD_MISS;
            }
        }
        return score;
    }

    // ── Attaques pseudo-légales (bitboard) ────────────────────────────────────

    /** Attaques de fou/dame en diagonale depuis {@code sq} avec occupation {@code occ}. */
    private static long bishopAttacks(int sq, long occ) {
        long attacks = 0L;
        attacks |= raySlide(sq, occ,  9, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ,  7, Bitboard.NOT_FILE_H);
        attacks |= raySlide(sq, occ, -7, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ, -9, Bitboard.NOT_FILE_H);
        return attacks;
    }

    /** Attaques de tour/dame en ligne depuis {@code sq} avec occupation {@code occ}. */
    private static long rookAttacks(int sq, long occ) {
        long attacks = 0L;
        attacks |= raySlide(sq, occ,  8, -1L);
        attacks |= raySlide(sq, occ, -8, -1L);
        attacks |= raySlide(sq, occ,  1, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ, -1, Bitboard.NOT_FILE_H);
        return attacks;
    }

    private static long raySlide(int sq, long occ, int shift, long noWrap) {
        long attacks = 0L;
        long cur = 1L << sq;
        for (int i = 0; i < 7; i++) {
            cur = shift > 0 ? (cur << shift) & noWrap : (cur >>> (-shift)) & noWrap;
            if (cur == 0) break;
            attacks |= cur;
            if ((cur & occ) != 0) break;
        }
        return attacks;
    }
}
