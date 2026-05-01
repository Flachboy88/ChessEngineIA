package com.chessoptia.core;

/**
 * Constantes et utilitaires pour la manipulation de bitboards.
 *
 * Conventions :
 *  - Un bitboard est un long 64 bits.
 *  - Bit N correspond à la case d'index N (0=a1, 63=h8).
 *  - Organisation : index = file + rank * 8
 *    (file 0=a..7=h, rank 0=1ère rangée..7=8ème rangée)
 */
public final class Bitboard {

    private Bitboard() {}

    // ── Masques de colonnes ──────────────────────────────────────────────────
    public static final long FILE_A = 0x0101010101010101L;
    public static final long FILE_B = FILE_A << 1;
    public static final long FILE_C = FILE_A << 2;
    public static final long FILE_D = FILE_A << 3;
    public static final long FILE_E = FILE_A << 4;
    public static final long FILE_F = FILE_A << 5;
    public static final long FILE_G = FILE_A << 6;
    public static final long FILE_H = FILE_A << 7;

    public static final long NOT_FILE_A = ~FILE_A;
    public static final long NOT_FILE_H = ~FILE_H;

    // ── Masques de rangées ───────────────────────────────────────────────────
    public static final long RANK_1 = 0xFFL;
    public static final long RANK_2 = RANK_1 << 8;
    public static final long RANK_3 = RANK_1 << 16;
    public static final long RANK_4 = RANK_1 << 24;
    public static final long RANK_5 = RANK_1 << 32;
    public static final long RANK_6 = RANK_1 << 40;
    public static final long RANK_7 = RANK_1 << 48;
    public static final long RANK_8 = RANK_1 << 56;

    // ── Diagonales utiles ────────────────────────────────────────────────────
    public static final long MAIN_DIAGONAL     = 0x8040201008040201L;
    public static final long ANTI_DIAGONAL     = 0x0102040810204080L;
    public static final long LIGHT_SQUARES     = 0x55AA55AA55AA55AAL;
    public static final long DARK_SQUARES      = ~LIGHT_SQUARES;

    // ── Cases de roque ───────────────────────────────────────────────────────
    /** Cases entre roi et tour côté roi (blancs) — doivent être vides. */
    public static final long WHITE_KINGSIDE_BETWEEN  = (1L << 5) | (1L << 6);
    /** Cases entre roi et tour côté dame (blancs) — doivent être vides. */
    public static final long WHITE_QUEENSIDE_BETWEEN = (1L << 1) | (1L << 2) | (1L << 3);
    /** Cases entre roi et tour côté roi (noirs) — doivent être vides. */
    public static final long BLACK_KINGSIDE_BETWEEN  = (1L << 61) | (1L << 62);
    /** Cases entre roi et tour côté dame (noirs) — doivent être vides. */
    public static final long BLACK_QUEENSIDE_BETWEEN = (1L << 57) | (1L << 58) | (1L << 59);

    /** Cases que le roi traverse (côté roi, blancs) — ne doivent pas être attaquées. */
    public static final long WHITE_KINGSIDE_KING_PATH  = (1L << 4) | (1L << 5) | (1L << 6);
    /** Cases que le roi traverse (côté dame, blancs) — ne doivent pas être attaquées. */
    public static final long WHITE_QUEENSIDE_KING_PATH = (1L << 2) | (1L << 3) | (1L << 4);
    /** Cases que le roi traverse (côté roi, noirs) — ne doivent pas être attaquées. */
    public static final long BLACK_KINGSIDE_KING_PATH  = (1L << 60) | (1L << 61) | (1L << 62);
    /** Cases que le roi traverse (côté dame, noirs) — ne doivent pas être attaquées. */
    public static final long BLACK_QUEENSIDE_KING_PATH = (1L << 58) | (1L << 59) | (1L << 60);

    // ── Opérations de base ───────────────────────────────────────────────────

    /** Retourne le masque d'une case donnée par son index. */
    public static long squareMask(int index) {
        return 1L << index;
    }

    /** Retourne vrai si le bit à l'index donné est allumé. */
    public static boolean isSet(long bb, int index) {
        return (bb & (1L << index)) != 0;
    }

    /** Allume le bit à l'index donné. */
    public static long setBit(long bb, int index) {
        return bb | (1L << index);
    }

    /** Éteint le bit à l'index donné. */
    public static long clearBit(long bb, int index) {
        return bb & ~(1L << index);
    }

    /** Retourne l'index du bit le moins significatif (LSB). */
    public static int lsb(long bb) {
        return Long.numberOfTrailingZeros(bb);
    }

    /** Retourne l'index du bit le plus significatif (MSB). */
    public static int msb(long bb) {
        return 63 - Long.numberOfLeadingZeros(bb);
    }

    /** Retire le bit le moins significatif et le retourne (pop LSB). */
    public static long popLsb(long bb) {
        return bb & (bb - 1);
    }

    /** Compte le nombre de bits allumés (population count). */
    public static int popCount(long bb) {
        return Long.bitCount(bb);
    }

    // ── Décalages directionnels (avec garde-fous de bord) ────────────────────

    public static long shiftNorth(long bb)     { return bb << 8; }
    public static long shiftSouth(long bb)     { return bb >>> 8; }
    public static long shiftEast(long bb)      { return (bb << 1) & NOT_FILE_A; }
    public static long shiftWest(long bb)      { return (bb >>> 1) & NOT_FILE_H; }
    public static long shiftNorthEast(long bb) { return (bb << 9) & NOT_FILE_A; }
    public static long shiftNorthWest(long bb) { return (bb << 7) & NOT_FILE_H; }
    public static long shiftSouthEast(long bb) { return (bb >>> 7) & NOT_FILE_A; }
    public static long shiftSouthWest(long bb) { return (bb >>> 9) & NOT_FILE_H; }

    // ── Affichage debug ──────────────────────────────────────────────────────

    /**
     * Affiche un bitboard sous forme de grille 8x8 (pour le debug).
     * Affiche la rangée 8 en haut, comme un échiquier classique.
     */
    public static String toBoardString(long bb) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append(" ");
            for (int file = 0; file < 8; file++) {
                int index = file + rank * 8;
                sb.append(isSet(bb, index) ? "1" : ".").append(" ");
            }
            sb.append("\n");
        }
        sb.append("  a b c d e f g h\n");
        return sb.toString();
    }
}
