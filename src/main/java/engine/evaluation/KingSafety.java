package engine.evaluation;

import core.Bitboard;
import core.BitboardState;
import model.Color;
import model.Piece;
import rules.AttackTables;
import rules.MagicBitboards;

/**
 * Évaluation de la sécurité du roi.
 * Les attaques glissantes utilisent {@link MagicBitboards} — O(1).
 *
 * <h2>Modèle d'attaque non-linéaire</h2>
 * Unités d'attaque accumulées par type de pièce ennemie dans la zone du roi,
 * transformées en pénalité via une table non-linéaire (danger exponentiel).
 */
public final class KingSafety {

    private static final int PAWN_SHIELD_CLOSE = 10;
    private static final int PAWN_SHIELD_FAR   =  5;
    private static final int PAWN_SHIELD_MISS  = -20;

    private static final int KNIGHT_ATTACK_WEIGHT = 2;
    private static final int BISHOP_ATTACK_WEIGHT = 2;
    private static final int ROOK_ATTACK_WEIGHT   = 3;
    private static final int QUEEN_ATTACK_WEIGHT  = 5;

    private static final int[] KING_DANGER_TABLE = {
          0,   0,   1,   2,   3,   5,   7,   9,  12,  15,
         18,  22,  26,  30,  35,  39,  44,  50,  56,  62,
         68,  75,  82,  85,  89,  97, 105, 113, 122, 131,
        140, 150, 169, 180, 191, 202, 213, 225, 237, 248,
        260, 272, 283, 295, 307, 319, 330, 342, 353, 364,
        375, 386, 396, 406, 416, 425, 434, 443, 451, 459,
        466, 473, 480, 486, 492, 497, 502, 506, 510, 514,
        517, 520, 522, 524, 526, 527, 528, 529, 530, 530
    };

    private static final int OPEN_FILE_NEAR_KING      = -12;
    private static final int OPEN_FILE_NEAR_KING_FULL = -20;

    private KingSafety() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le score de sécurité du roi du point de vue des Blancs.
     * Score positif = les Blancs sont plus en sécurité.
     * Multiplié par la phase (évaluation MG principalement).
     *
     * @param state état courant
     * @param phase256 phase de jeu (1.0 = ouverture, 0.0 = finale)
     * @return score de sécurité pondéré
     */
    public static int evaluate(BitboardState state, int phase256) {
        int whiteScore = evaluateSide(state, Color.WHITE);
        int blackScore = evaluateSide(state, Color.BLACK);
        return (whiteScore - blackScore) * phase256 >> 8;
    }

    private static int evaluateSide(BitboardState state, Color us) {
        long kingBB = state.getBitboard(us, Piece.KING);
        if (kingBB == 0) return 0;

        int kingSq   = Long.numberOfTrailingZeros(kingBB);
        int kingFile = kingSq % 8;
        boolean white = (us == Color.WHITE);

        long ourPawns = state.getBitboard(us, Piece.PAWN);
        long occ      = state.getAllOccupancy();

        int score       = 0;
        int attackUnits = 0;

        // ── 1. Bouclier de pions ─────────────────────────────────────────────
        if (kingFile <= 2 || kingFile >= 5) {
            score += pawnShieldScore(kingSq, kingFile, ourPawns, white);
        }

        // ── 2. Attaques ennemies sur la zone du roi ───────────────────────────
        long kingZone = AttackTables.KING_ATTACKS[kingSq] | kingBB;
        Color them = us.opposite();

        long temp = state.getBitboard(them, Piece.KNIGHT);
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            if ((AttackTables.KNIGHT_ATTACKS[sq] & kingZone) != 0)
                attackUnits += KNIGHT_ATTACK_WEIGHT;
        }

        temp = state.getBitboard(them, Piece.BISHOP);
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            if ((MagicBitboards.getBishopAttacks(sq, occ) & kingZone) != 0)
                attackUnits += BISHOP_ATTACK_WEIGHT;
        }

        temp = state.getBitboard(them, Piece.ROOK);
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            if ((MagicBitboards.getRookAttacks(sq, occ) & kingZone) != 0)
                attackUnits += ROOK_ATTACK_WEIGHT;
        }

        temp = state.getBitboard(them, Piece.QUEEN);
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            if ((MagicBitboards.getQueenAttacks(sq, occ) & kingZone) != 0)
                attackUnits += QUEEN_ATTACK_WEIGHT;
        }

        int tableIdx = Math.min(attackUnits, KING_DANGER_TABLE.length - 1);
        score -= KING_DANGER_TABLE[tableIdx];

        // ── 3. Colonnes ouvertes proches du roi ───────────────────────────────
        long theirPawns = state.getBitboard(them, Piece.PAWN);
        int fileMin = Math.max(0, kingFile - 1);
        int fileMax = Math.min(7, kingFile + 1);
        for (int f = fileMin; f <= fileMax; f++) {
            long col    = Bitboard.FILE_A << f;
            boolean noOur   = (ourPawns   & col) == 0;
            boolean noTheir = (theirPawns & col) == 0;
            if (noOur && noTheir) {
                score += OPEN_FILE_NEAR_KING_FULL;
            } else if (noOur) {
                score += OPEN_FILE_NEAR_KING;
            }
        }

        return score;
    }

    private static int pawnShieldScore(int kingSq, int kingFile,
                                       long ourPawns, boolean white) {
        int score   = 0;
        int fileMin = Math.max(0, kingFile - 1);
        int fileMax = Math.min(7, kingFile + 1);

        for (int f = fileMin; f <= fileMax; f++) {
            long col = Bitboard.FILE_A << f;
            long closeRank = white
                ? (Bitboard.RANK_1 << ((kingSq / 8 + 1) * 8))
                : (Bitboard.RANK_1 << ((kingSq / 8 - 1) * 8));
            long farRank = white
                ? (Bitboard.RANK_1 << ((kingSq / 8 + 2) * 8))
                : (Bitboard.RANK_1 << ((kingSq / 8 - 2) * 8));

            if ((ourPawns & col & closeRank) != 0) {
                score += PAWN_SHIELD_CLOSE;
            } else if ((ourPawns & col & farRank) != 0) {
                score += PAWN_SHIELD_FAR;
            } else {
                score += PAWN_SHIELD_MISS;
            }
        }
        return score;
    }
}
