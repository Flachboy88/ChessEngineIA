package engine.search;

import model.Color;

import java.util.Random;

/**
 * Générateur de hash Zobrist pour les positions d'échecs.
 *
 * <p>Principe : on associe un nombre aléatoire 64-bit à chaque combinaison
 * (pièce, couleur, case). Le hash d'une position est le XOR de tous ces
 * nombres pour chaque pièce présente sur l'échiquier.
 *
 * <p>Avantage clé : quand on joue un coup, il suffit de XOR les valeurs des
 * pièces qui bougent — pas besoin de recalculer tout le hash depuis zéro.
 *
 * <p>Cette classe est un singleton statique : les tables aléatoires sont
 * initialisées une seule fois au chargement de la classe (graine fixe pour
 * la reproductibilité).
 *
 * <h2>Index des pièces (piece.index)</h2>
 * 0=PAWN, 1=KNIGHT, 2=BISHOP, 3=ROOK, 4=QUEEN, 5=KING
 */
public final class ZobristHasher {

    /** Hash par [couleur][pièce][case] — 2 × 6 × 64 = 768 valeurs. */
    public static final long[][][] PIECE_HASH = new long[2][6][64];

    /** Hash pour chaque colonne possible de prise en passant (0-7). */
    public static final long[] EN_PASSANT_HASH = new long[8];

    /**
     * Hash des droits de roque — 4 bits possibles (0-15).
     * Bit 0 = roque petit Blanc, 1 = grand Blanc, 2 = petit Noir, 3 = grand Noir.
     */
    public static final long[] CASTLING_HASH = new long[16];

    /** XOR ce hash pour indiquer que c'est aux Noirs de jouer. */
    public static final long SIDE_TO_MOVE_HASH;

    static {
        Random rng = new Random(0xDEAD_BEEF_CAFE_1234L); // graine fixe = reproductibilité
        for (int c = 0; c < 2; c++)
            for (int p = 0; p < 6; p++)
                for (int sq = 0; sq < 64; sq++)
                    PIECE_HASH[c][p][sq] = rng.nextLong();

        for (int f = 0; f < 8; f++)
            EN_PASSANT_HASH[f] = rng.nextLong();

        for (int mask = 0; mask < 16; mask++)
            CASTLING_HASH[mask] = rng.nextLong();

        SIDE_TO_MOVE_HASH = rng.nextLong();
    }

    private ZobristHasher() {}

    /**
     * Calcule le hash complet d'un état à partir de zéro.
     * Utilisé uniquement lors de la création d'un BitboardState (via FEN ou
     * position initiale). Ensuite, les mises à jour sont incrémentales via
     * {@code xorPiece}, {@code toggleSide}, etc.
     *
     * @param bitboards     [couleur][pièce] → bitboard
     * @param sideToMove    couleur qui joue
     * @param castlingMask  droits de roque (4 bits)
     * @param enPassantFile colonne en passant (-1 si aucune)
     * @return hash Zobrist de la position
     */
    public static long computeHash(long[][] bitboards, Color sideToMove,
                                   int castlingMask, int enPassantFile) {
        long hash = 0L;

        for (int c = 0; c < 2; c++) {
            for (int p = 0; p < 6; p++) {
                long bb = bitboards[c][p];
                while (bb != 0) {
                    int sq = Long.numberOfTrailingZeros(bb);
                    bb &= bb - 1;
                    hash ^= PIECE_HASH[c][p][sq];
                }
            }
        }

        if (sideToMove == Color.BLACK) hash ^= SIDE_TO_MOVE_HASH;
        hash ^= CASTLING_HASH[castlingMask & 0xF];
        if (enPassantFile >= 0) hash ^= EN_PASSANT_HASH[enPassantFile];

        return hash;
    }
}
