package engine.evaluation;

import core.Bitboard;
import core.BitboardState;
import model.Color;
import model.Piece;
import rules.AttackTables;
import rules.MagicBitboards;

/**
 * Évaluation de la mobilité des pièces (approche pseudo-légale).
 * Les attaques glissantes utilisent {@link MagicBitboards} — O(1).
 */
public final class MobilityEvaluator {

    private static final int KNIGHT_MOB_MG = 4;
    private static final int KNIGHT_MOB_EG = 4;
    private static final int BISHOP_MOB_MG = 4;
    private static final int BISHOP_MOB_EG = 5;
    private static final int ROOK_MOB_MG   = 2;
    private static final int ROOK_MOB_EG   = 4;
    private static final int QUEEN_MOB_MG  = 1;
    private static final int QUEEN_MOB_EG  = 2;

    private static final int BISHOP_PAIR_MG       = 30;
    private static final int BISHOP_PAIR_EG       = 50;

    private static final int ROOK_OPEN_FILE        = 25;
    private static final int ROOK_SEMI_OPEN_FILE   = 12;
    private static final int ROOK_SEVENTH_RANK     = 20;
    private static final int ROOK_DOUBLED_MG       = 15;
    private static final int ROOK_DOUBLED_EG       = 10;

    private static final int QUEEN_ROOK_BATTERY_MG = 10;

    private MobilityEvaluator() {}

    public static int evaluate(BitboardState state, int phase256) {
        return evaluateSide(state, Color.WHITE, phase256)
             - evaluateSide(state, Color.BLACK, phase256);
    }

    private static int evaluateSide(BitboardState state, Color us, int phase256) {
        long occ      = state.getAllOccupancy();
        long ourPawns = state.getBitboard(us, Piece.PAWN);
        long ourKing  = state.getBitboard(us, Piece.KING);
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
        if (Long.bitCount(bishops) >= 2) {
            scoreMg += BISHOP_PAIR_MG;
            scoreEg += BISHOP_PAIR_EG;
        }
        temp = bishops;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = MagicBitboards.getBishopAttacks(sq, occ) & ~excluded;
            scoreMg += Long.bitCount(attacks) * BISHOP_MOB_MG;
            scoreEg += Long.bitCount(attacks) * BISHOP_MOB_EG;
        }

        // ── Tours ─────────────────────────────────────────────────────────────
        long rooks        = state.getBitboard(us, Piece.ROOK);
        long theirPawns   = state.getBitboard(us.opposite(), Piece.PAWN);
        boolean white     = (us == Color.WHITE);
        int seventhRank   = white ? 6 : 1;
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

            long attacks = MagicBitboards.getRookAttacks(sq, occ) & ~excluded;
            scoreMg += Long.bitCount(attacks) * ROOK_MOB_MG;
            scoreEg += Long.bitCount(attacks) * ROOK_MOB_EG;

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

            if (rank == seventhRank && theirKingRank == eighthRank) {
                scoreMg += ROOK_SEVENTH_RANK;
            }

            long otherRooks = rooks & ~(1L << sq);
            if ((otherRooks & col) != 0) {
                scoreMg += ROOK_DOUBLED_MG;
                scoreEg += ROOK_DOUBLED_EG;
            }
        }

        // ── Dames ─────────────────────────────────────────────────────────────
        long queens = state.getBitboard(us, Piece.QUEEN);
        temp = queens;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            temp &= temp - 1;
            long attacks = MagicBitboards.getQueenAttacks(sq, occ) & ~excluded;
            scoreMg += Long.bitCount(attacks) * QUEEN_MOB_MG;
            scoreEg += Long.bitCount(attacks) * QUEEN_MOB_EG;

            if (rooks != 0) {
                int queenFile = sq % 8;
                int queenRank = sq / 8;
                long rooksTemp = rooks;
                while (rooksTemp != 0) {
                    int rsq = Long.numberOfTrailingZeros(rooksTemp);
                    rooksTemp &= rooksTemp - 1;
                    if (rsq % 8 == queenFile || rsq / 8 == queenRank) {
                        scoreMg += QUEEN_ROOK_BATTERY_MG;
                        break;
                    }
                }
            }
        }

        return PawnEvaluator.interpolate(scoreMg, scoreEg, phase256);
    }
}
