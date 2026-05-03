package engine.evaluation;

import core.Bitboard;
import core.BitboardState;
import model.Color;
import model.Piece;
import rules.AttackTables;

/**
 * Évaluation de la mobilité des pièces (approche pseudo-légale).
 *
 * <h2>Principe</h2>
 * Pour chaque pièce (cavalier, fou, tour, dame), on compte le nombre de cases
 * atteignables pseudo-légalement (sans vérifier les clouages) et on ajoute un bonus
 * proportionnel. Plus une pièce a de cases disponibles, plus elle est active.
 *
 * <h2>Pourquoi pseudo-légal ?</h2>
 * Compter les coups <em>légaux</em> nécessiterait d'appeler {@code MoveGenerator.generateLegalMoves}
 * pour chaque pièce — environ 10× plus lent. La pseudo-légalité est la pratique standard
 * de tous les moteurs sérieux (Stockfish, Crafty, Ethereal…). Le léger manque de précision
 * (les pièces clouées semblent plus mobiles qu'elles ne le sont) est compensé par l'évaluation
 * du roi et des clouages qui apparaissent dans d'autres termes.
 *
 * <h2>Zones exclues</h2>
 * Pour éviter de compter des cases inutilement, on exclut :
 * <ul>
 *   <li>Les cases occupées par des pions alliés (blocage évident)</li>
 *   <li>Les cases occupées par le roi allié (inutile à compter)</li>
 * </ul>
 * Les captures ennemies et les cases vides sont toutes comptées.
 *
 * <h2>Facteurs de mobilité</h2>
 * Issus de la littérature sur la programmation d'échecs (CPW, Stockfish parameters) :
 * <pre>
 *   Cavalier : MG=+4, EG=+4  (très dépendant des cases disponibles)
 *   Fou      : MG=+5, EG=+5  (précieux sur les grandes diagonales)
 *   Tour     : MG=+2, EG=+4  (plus précieuse en finale sur colonnes ouvertes)
 *   Dame     : MG=+1, EG=+2  (beaucoup de cases par nature → bonus faible par case)
 * </pre>
 *
 * <h2>Bonus supplémentaires</h2>
 * <ul>
 *   <li><b>Paire de fous</b> — +30 MG / +40 EG si les deux fous sont présents.
 *       Exceptionnel en finale ouverte.</li>
 *   <li><b>Tour colonne ouverte</b> — +20 si aucun pion sur la colonne.</li>
 *   <li><b>Tour colonne semi-ouverte</b> — +10 si pas de pion allié mais un pion ennemi.</li>
 *   <li><b>Tour 7e rang</b> — +15 MG si sur le 7e rang et roi ennemi sur le 8e.</li>
 * </ul>
 */
public final class MobilityEvaluator {

    // ── Facteurs de mobilité [MG, EG] ────────────────────────────────────────
    private static final int KNIGHT_MOB_MG = 4;
    private static final int KNIGHT_MOB_EG = 4;
    private static final int BISHOP_MOB_MG = 5;
    private static final int BISHOP_MOB_EG = 5;
    private static final int ROOK_MOB_MG   = 2;
    private static final int ROOK_MOB_EG   = 4;
    private static final int QUEEN_MOB_MG  = 1;
    private static final int QUEEN_MOB_EG  = 2;

    // ── Bonus paire de fous ───────────────────────────────────────────────────
    private static final int BISHOP_PAIR_MG = 30;
    private static final int BISHOP_PAIR_EG = 40;

    // ── Bonus tour sur colonne ────────────────────────────────────────────────
    private static final int ROOK_OPEN_FILE      = 20;
    private static final int ROOK_SEMI_OPEN_FILE = 10;
    /** Tour sur le 7e rang avec roi ennemi sur le 8e. */
    private static final int ROOK_SEVENTH_RANK   = 15;

    private MobilityEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le score de mobilité du point de vue des Blancs.
     * Score positif = les Blancs ont plus de mobilité.
     *
     * @param state état courant
     * @param phase phase de jeu (1.0 = ouverture, 0.0 = finale)
     * @return score interpolé MG/EG
     */
    public static int evaluate(BitboardState state, double phase) {
        return evaluateSide(state, Color.WHITE, phase)
             - evaluateSide(state, Color.BLACK, phase);
    }

    // ── Évaluation par camp ───────────────────────────────────────────────────

    private static int evaluateSide(BitboardState state, Color us, double phase) {
        long occ      = state.getAllOccupancy();
        long ourPawns = state.getBitboard(us, Piece.PAWN);
        long ourKing  = state.getBitboard(us, Piece.KING);
        // Zones à exclure : nos propres pions et notre roi
        long excluded = ourPawns | ourKing;

        int scoreMg = 0;
        int scoreEg = 0;

        // ── Cavaliers ────────────────────────────────────────────────────────
        long knights = state.getBitboard(us, Piece.KNIGHT);
        long temp = knights;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            int mobility = Long.bitCount(AttackTables.KNIGHT_ATTACKS[sq] & ~excluded);
            scoreMg += mobility * KNIGHT_MOB_MG;
            scoreEg += mobility * KNIGHT_MOB_EG;
        }

        // ── Fous ─────────────────────────────────────────────────────────────
        long bishops = state.getBitboard(us, Piece.BISHOP);
        // Paire de fous
        if (Long.bitCount(bishops) >= 2) {
            scoreMg += BISHOP_PAIR_MG;
            scoreEg += BISHOP_PAIR_EG;
        }
        temp = bishops;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = bishopAttacks(sq, occ) & ~excluded;
            scoreMg += Long.bitCount(attacks) * BISHOP_MOB_MG;
            scoreEg += Long.bitCount(attacks) * BISHOP_MOB_EG;
        }

        // ── Tours ─────────────────────────────────────────────────────────────
        long rooks        = state.getBitboard(us, Piece.ROOK);
        long theirPawns   = state.getBitboard(us.opposite(), Piece.PAWN);
        boolean white     = (us == Color.WHITE);
        int seventhRank   = white ? 6 : 1; // rang index (0-7)
        long theirKingBB  = state.getBitboard(us.opposite(), Piece.KING);
        int theirKingRank = (theirKingBB == 0) ? -1
                          : Long.numberOfTrailingZeros(theirKingBB) / 8;
        int eighthRank    = white ? 7 : 0;

        temp = rooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            int file = sq % 8;
            int rank = sq / 8;

            long attacks = rookAttacks(sq, occ) & ~excluded;
            scoreMg += Long.bitCount(attacks) * ROOK_MOB_MG;
            scoreEg += Long.bitCount(attacks) * ROOK_MOB_EG;

            // Bonus colonne ouverte / semi-ouverte
            long col = Bitboard.FILE_A << file;
            boolean noOurPawn   = (ourPawns   & col) == 0;
            boolean noTheirPawn = (theirPawns & col) == 0;
            if (noOurPawn && noTheirPawn) {
                scoreMg += ROOK_OPEN_FILE;
                scoreEg += ROOK_OPEN_FILE;
            } else if (noOurPawn) {
                scoreMg += ROOK_SEMI_OPEN_FILE;
                scoreEg += ROOK_SEMI_OPEN_FILE;
            }

            // Bonus 7e rang (tour très active, cloue souvent le roi)
            if (rank == seventhRank && theirKingRank == eighthRank) {
                scoreMg += ROOK_SEVENTH_RANK;
            }
        }

        // ── Dames ─────────────────────────────────────────────────────────────
        long queens = state.getBitboard(us, Piece.QUEEN);
        temp = queens;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = (bishopAttacks(sq, occ) | rookAttacks(sq, occ)) & ~excluded;
            scoreMg += Long.bitCount(attacks) * QUEEN_MOB_MG;
            scoreEg += Long.bitCount(attacks) * QUEEN_MOB_EG;
        }

        return PawnEvaluator.interpolate(scoreMg, scoreEg, phase);
    }

    // ── Attaques pseudo-légales ───────────────────────────────────────────────

    private static long bishopAttacks(int sq, long occ) {
        long a = 0L;
        a |= raySlide(sq, occ,  9, Bitboard.NOT_FILE_A);
        a |= raySlide(sq, occ,  7, Bitboard.NOT_FILE_H);
        a |= raySlide(sq, occ, -7, Bitboard.NOT_FILE_A);
        a |= raySlide(sq, occ, -9, Bitboard.NOT_FILE_H);
        return a;
    }

    private static long rookAttacks(int sq, long occ) {
        long a = 0L;
        a |= raySlide(sq, occ,  8, -1L);
        a |= raySlide(sq, occ, -8, -1L);
        a |= raySlide(sq, occ,  1, Bitboard.NOT_FILE_A);
        a |= raySlide(sq, occ, -1, Bitboard.NOT_FILE_H);
        return a;
    }

    private static long raySlide(int sq, long occ, int shift, long noWrap) {
        long a = 0L, cur = 1L << sq;
        for (int i = 0; i < 7; i++) {
            cur = shift > 0 ? (cur << shift) & noWrap : (cur >>> (-shift)) & noWrap;
            if (cur == 0) break;
            a |= cur;
            if ((cur & occ) != 0) break;
        }
        return a;
    }
}
