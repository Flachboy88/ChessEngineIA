package com.chessoptia.core;

import com.chessoptia.model.Color;
import com.chessoptia.model.Piece;
import com.chessoptia.model.Square;
import com.chessoptia.model.CastlingRights;

/**
 * État complet de l'échiquier encodé en bitboards.
 *
 * 12 bitboards : un par (Pièce × Couleur).
 * Index : bitboards[color.ordinal()][piece.index]
 *   - [0][0] = pions blancs, [0][5] = roi blanc
 *   - [1][0] = pions noirs,  [1][5] = roi noir
 *
 * Cette classe est immuable — toute modification retourne un nouvel état.
 */
public final class BitboardState {

    /** bitboards[couleur][pièce] */
    private final long[][] bitboards;

    /** Tour au jeu. */
    private final Color sideToMove;

    /** Droits de roque. */
    private final CastlingRights castlingRights;

    /**
     * Case cible de prise en passant (null si aucune).
     * C'est la case où le pion capturant doit aller (pas la case du pion capturé).
     */
    private final Square enPassantTarget;

    /** Compteur de demi-coups pour la règle des 50 coups. */
    private final int halfMoveClock;

    /** Numéro du coup complet (commence à 1, incrémenté après le coup des noirs). */
    private final int fullMoveNumber;

    // ── Constructeur privé ────────────────────────────────────────────────────

    private BitboardState(long[][] bitboards, Color sideToMove,
                          CastlingRights castlingRights, Square enPassantTarget,
                          int halfMoveClock, int fullMoveNumber) {
        this.bitboards       = bitboards;
        this.sideToMove      = sideToMove;
        this.castlingRights  = castlingRights;
        this.enPassantTarget = enPassantTarget;
        this.halfMoveClock   = halfMoveClock;
        this.fullMoveNumber  = fullMoveNumber;
    }

    // ── Position initiale ─────────────────────────────────────────────────────

    /** Retourne l'état de la position initiale standard. */
    public static BitboardState initialPosition() {
        long[][] bbs = new long[2][6];

        // Blancs (index 0)
        bbs[0][Piece.PAWN.index]   = Bitboard.RANK_2;
        bbs[0][Piece.KNIGHT.index] = (1L << Square.B1.index) | (1L << Square.G1.index);
        bbs[0][Piece.BISHOP.index] = (1L << Square.C1.index) | (1L << Square.F1.index);
        bbs[0][Piece.ROOK.index]   = (1L << Square.A1.index) | (1L << Square.H1.index);
        bbs[0][Piece.QUEEN.index]  = (1L << Square.D1.index);
        bbs[0][Piece.KING.index]   = (1L << Square.E1.index);

        // Noirs (index 1)
        bbs[1][Piece.PAWN.index]   = Bitboard.RANK_7;
        bbs[1][Piece.KNIGHT.index] = (1L << Square.B8.index) | (1L << Square.G8.index);
        bbs[1][Piece.BISHOP.index] = (1L << Square.C8.index) | (1L << Square.F8.index);
        bbs[1][Piece.ROOK.index]   = (1L << Square.A8.index) | (1L << Square.H8.index);
        bbs[1][Piece.QUEEN.index]  = (1L << Square.D8.index);
        bbs[1][Piece.KING.index]   = (1L << Square.E8.index);

        return new BitboardState(bbs, Color.WHITE, CastlingRights.all(), null, 0, 1);
    }

    /** Retourne un état vide (aucune pièce). */
    public static BitboardState empty() {
        return new BitboardState(new long[2][6], Color.WHITE, CastlingRights.none(), null, 0, 1);
    }

    // ── Accesseurs bitboards ──────────────────────────────────────────────────

    public long getBitboard(Color color, Piece piece) {
        return bitboards[color.ordinal()][piece.index];
    }

    /** Toutes les pièces d'une couleur. */
    public long getOccupancy(Color color) {
        long occ = 0L;
        for (int i = 0; i < 6; i++) occ |= bitboards[color.ordinal()][i];
        return occ;
    }

    /** Toutes les pièces sur l'échiquier. */
    public long getAllOccupancy() {
        return getOccupancy(Color.WHITE) | getOccupancy(Color.BLACK);
    }

    /**
     * Retourne la pièce présente sur une case, ou null si vide.
     * Résultat : tableau {Color, Piece} ou null.
     */
    public PieceOnSquare getPieceAt(Square square) {
        for (Color color : Color.values()) {
            for (Piece piece : Piece.values()) {
                if (Bitboard.isSet(bitboards[color.ordinal()][piece.index], square.index)) {
                    return new PieceOnSquare(color, piece);
                }
            }
        }
        return null;
    }

    // ── Accesseurs état ───────────────────────────────────────────────────────

    public Color getSideToMove()      { return sideToMove; }
    public CastlingRights getCastlingRights() { return castlingRights; }
    public Square getEnPassantTarget() { return enPassantTarget; }
    public int getHalfMoveClock()     { return halfMoveClock; }
    public int getFullMoveNumber()    { return fullMoveNumber; }

    // ── Builders (retournent un nouvel état immuable) ─────────────────────────

    /** Pose une pièce sur une case (retourne un nouvel état). */
    public BitboardState withPiece(Color color, Piece piece, Square square) {
        long[][] newBbs = copyBitboards();
        newBbs[color.ordinal()][piece.index] = Bitboard.setBit(
            newBbs[color.ordinal()][piece.index], square.index);
        return new BitboardState(newBbs, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber);
    }

    /** Retire une pièce d'une case (retourne un nouvel état). */
    public BitboardState withoutPiece(Color color, Piece piece, Square square) {
        long[][] newBbs = copyBitboards();
        newBbs[color.ordinal()][piece.index] = Bitboard.clearBit(
            newBbs[color.ordinal()][piece.index], square.index);
        return new BitboardState(newBbs, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber);
    }

    public BitboardState withSideToMove(Color color) {
        return new BitboardState(bitboards, color, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber);
    }

    public BitboardState withCastlingRights(CastlingRights rights) {
        return new BitboardState(bitboards, sideToMove, rights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber);
    }

    public BitboardState withEnPassantTarget(Square ep) {
        return new BitboardState(bitboards, sideToMove, castlingRights, ep,
                                 halfMoveClock, fullMoveNumber);
    }

    public BitboardState withHalfMoveClock(int clock) {
        return new BitboardState(bitboards, sideToMove, castlingRights, enPassantTarget,
                                 clock, fullMoveNumber);
    }

    public BitboardState withFullMoveNumber(int number) {
        return new BitboardState(bitboards, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, number);
    }

    /** Retourne une copie complète des bitboards. */
    public BitboardState copy() {
        return new BitboardState(copyBitboards(), sideToMove, castlingRights,
                                 enPassantTarget, halfMoveClock, fullMoveNumber);
    }

    private long[][] copyBitboards() {
        long[][] copy = new long[2][6];
        for (int c = 0; c < 2; c++) {
            System.arraycopy(bitboards[c], 0, copy[c], 0, 6);
        }
        return copy;
    }

    // ── Record utilitaire ─────────────────────────────────────────────────────

    /** Paire (couleur, pièce) présente sur une case. */
    public record PieceOnSquare(Color color, Piece piece) {}
}
