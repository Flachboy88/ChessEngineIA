package com.chessoptia.rules;

import com.chessoptia.core.Bitboard;
import com.chessoptia.model.Square;

/**
 * Tables d'attaques précalculées pour cavaliers et rois.
 * Les attaques des pièces glissantes (fous, tours, dames) sont calculées
 * dynamiquement via les méthodes de rayon dans MoveGenerator.
 *
 * Les tables sont initialisées une seule fois au chargement de la classe.
 */
public final class AttackTables {

    private AttackTables() {}

    /** Attaques du cavalier depuis chaque case (0-63). */
    public static final long[] KNIGHT_ATTACKS = new long[64];

    /** Attaques du roi depuis chaque case (0-63). */
    public static final long[] KING_ATTACKS = new long[64];

    /** Attaques de pion blanc depuis chaque case (0-63). */
    public static final long[] WHITE_PAWN_ATTACKS = new long[64];

    /** Attaques de pion noir depuis chaque case (0-63). */
    public static final long[] BLACK_PAWN_ATTACKS = new long[64];

    static {
        for (int sq = 0; sq < 64; sq++) {
            long bb = 1L << sq;
            KNIGHT_ATTACKS[sq]     = computeKnightAttacks(bb);
            KING_ATTACKS[sq]       = computeKingAttacks(bb);
            WHITE_PAWN_ATTACKS[sq] = computeWhitePawnAttacks(bb);
            BLACK_PAWN_ATTACKS[sq] = computeBlackPawnAttacks(bb);
        }
    }

    private static long computeKnightAttacks(long bb) {
        long attacks = 0L;
        attacks |= (bb << 17) & Bitboard.NOT_FILE_A; // Nord-Nord-Est
        attacks |= (bb << 15) & Bitboard.NOT_FILE_H; // Nord-Nord-Ouest
        attacks |= (bb << 10) & (Bitboard.NOT_FILE_A & ~Bitboard.FILE_B); // Nord-Est-Est
        attacks |= (bb <<  6) & (Bitboard.NOT_FILE_H & ~Bitboard.FILE_G); // Nord-Ouest-Ouest
        attacks |= (bb >>> 15) & Bitboard.NOT_FILE_A; // Sud-Sud-Est
        attacks |= (bb >>> 17) & Bitboard.NOT_FILE_H; // Sud-Sud-Ouest
        attacks |= (bb >>>  6) & (Bitboard.NOT_FILE_A & ~Bitboard.FILE_B); // Sud-Est-Est
        attacks |= (bb >>> 10) & (Bitboard.NOT_FILE_H & ~Bitboard.FILE_G); // Sud-Ouest-Ouest
        return attacks;
    }

    private static long computeKingAttacks(long bb) {
        long attacks = 0L;
        attacks |= Bitboard.shiftNorth(bb);
        attacks |= Bitboard.shiftSouth(bb);
        attacks |= Bitboard.shiftEast(bb);
        attacks |= Bitboard.shiftWest(bb);
        attacks |= Bitboard.shiftNorthEast(bb);
        attacks |= Bitboard.shiftNorthWest(bb);
        attacks |= Bitboard.shiftSouthEast(bb);
        attacks |= Bitboard.shiftSouthWest(bb);
        return attacks;
    }

    private static long computeWhitePawnAttacks(long bb) {
        return Bitboard.shiftNorthEast(bb) | Bitboard.shiftNorthWest(bb);
    }

    private static long computeBlackPawnAttacks(long bb) {
        return Bitboard.shiftSouthEast(bb) | Bitboard.shiftSouthWest(bb);
    }

    /**
     * Calcule les attaques d'une pièce glissante sur un rayon donné (ex: fou en diagonale).
     * S'arrête à la première pièce rencontrée (incluse si ennemie, exclue si alliée).
     *
     * @param sq         case de départ
     * @param occupancy  toutes les pièces sur l'échiquier
     * @param allies     pièces alliées (à exclure des cibles)
     * @param shift      décalage positif (ex: +7, +8, +9) ou négatif (ex: -7, -8, -9)
     * @param edgeMask   masque à appliquer pour éviter le wrap-around (NOT_FILE_A ou NOT_FILE_H, ou 0xFFFFFFFFFFFFFFFFL)
     */
    public static long rayAttacks(int sq, long occupancy, long allies, int shift, long edgeMask) {
        long attacks = 0L;
        long current = 1L << sq;
        while (true) {
            if (shift > 0) {
                current = (current << shift) & edgeMask;
            } else {
                current = (current >>> (-shift)) & edgeMask;
            }
            if (current == 0) break;
            attacks |= current;
            if ((current & occupancy) != 0) break; // bloqué par une pièce
        }
        return attacks & ~allies;
    }
}
