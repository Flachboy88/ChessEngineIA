package core;

import model.Color;
import model.Piece;
import model.Square;
import model.CastlingRights;

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

    /**
     * Hash Zobrist de cette position.
     * Calculé une fois à la création, mis à jour incrémentalement dans applyMove.
     * Permet d'identifier rapidement les positions déjà vues (table de transposition).
     */
    private final long zobristHash;

    // ── Constructeur privé ────────────────────────────────────────────────────

    private BitboardState(long[][] bitboards, Color sideToMove,
                          CastlingRights castlingRights, Square enPassantTarget,
                          int halfMoveClock, int fullMoveNumber, long zobristHash) {
        this.bitboards       = bitboards;
        this.sideToMove      = sideToMove;
        this.castlingRights  = castlingRights;
        this.enPassantTarget = enPassantTarget;
        this.halfMoveClock   = halfMoveClock;
        this.fullMoveNumber  = fullMoveNumber;
        this.zobristHash     = zobristHash;
    }

    /** Constructeur interne sans hash pré-calculé — le hash est calculé depuis zéro. */
    private BitboardState(long[][] bitboards, Color sideToMove,
                          CastlingRights castlingRights, Square enPassantTarget,
                          int halfMoveClock, int fullMoveNumber) {
        this.bitboards       = bitboards;
        this.sideToMove      = sideToMove;
        this.castlingRights  = castlingRights;
        this.enPassantTarget = enPassantTarget;
        this.halfMoveClock   = halfMoveClock;
        this.fullMoveNumber  = fullMoveNumber;
        int epFile = (enPassantTarget != null) ? enPassantTarget.file : -1;
        this.zobristHash = ZobristHasher.computeHash(
                bitboards, sideToMove, castlingRights.getRights(), epFile);
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

    /**
     * Constructeur tout-en-un utilisé par MoveGenerator.applyMove.
     * Évite les 8-10 allocations intermédiaires de la chaîne withXxx().
     */
    public static BitboardState ofApplied(long[][] bitboards, Color sideToMove,
                                          CastlingRights castlingRights, Square enPassantTarget,
                                          int halfMoveClock, int fullMoveNumber, long zobristHash) {
        return new BitboardState(bitboards, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber, zobristHash);
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
    /** Retourne le hash Zobrist de cette position (pour la table de transposition). */
    public long getZobristHash()      { return zobristHash; }

    /**
     * Expose une ligne du tableau de bitboards (utilisé par applyMove pour copie rapide).
     */
    public long[] getBitboardsRow(int colorOrdinal) {
        return bitboards[colorOrdinal];
    }

    // ── Builders (retournent un nouvel état immuable) ─────────────────────────

    // ── Builders avec mise à jour incrémentale du hash Zobrist ──────────────
    //
    // Chaque builder prend en paramètre le hash courant et applique les XOR
    // nécessaires pour refléter le changement. C'est quasi-gratuit (1-2 XOR)
    // comparé à un recalcul complet depuis zéro.

    /**
     * Pose une pièce sur une case (retourne un nouvel état).
     * Met à jour le hash Zobrist incrémentalement.
     */
    public BitboardState withPiece(Color color, Piece piece, Square square) {
        long[][] newBbs = copyBitboards();
        newBbs[color.ordinal()][piece.index] = Bitboard.setBit(
            newBbs[color.ordinal()][piece.index], square.index);
        long newHash = zobristHash ^ ZobristHasher.PIECE_HASH[color.ordinal()][piece.index][square.index];
        return new BitboardState(newBbs, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber, newHash);
    }

    /**
     * Retire une pièce d'une case (retourne un nouvel état).
     * Met à jour le hash Zobrist incrémentalement (XOR = retrait).
     */
    public BitboardState withoutPiece(Color color, Piece piece, Square square) {
        long[][] newBbs = copyBitboards();
        newBbs[color.ordinal()][piece.index] = Bitboard.clearBit(
            newBbs[color.ordinal()][piece.index], square.index);
        long newHash = zobristHash ^ ZobristHasher.PIECE_HASH[color.ordinal()][piece.index][square.index];
        return new BitboardState(newBbs, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber, newHash);
    }

    public BitboardState withSideToMove(Color color) {
        // XOR le hash de changement de tour si la couleur change réellement
        long newHash = (color != sideToMove)
            ? zobristHash ^ ZobristHasher.SIDE_TO_MOVE_HASH
            : zobristHash;
        return new BitboardState(bitboards, color, castlingRights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber, newHash);
    }

    public BitboardState withCastlingRights(CastlingRights rights) {
        // On retire l'ancien masque de roque et on ajoute le nouveau
        long newHash = zobristHash
            ^ ZobristHasher.CASTLING_HASH[castlingRights.getRights()]
            ^ ZobristHasher.CASTLING_HASH[rights.getRights()];
        return new BitboardState(bitboards, sideToMove, rights, enPassantTarget,
                                 halfMoveClock, fullMoveNumber, newHash);
    }

    public BitboardState withEnPassantTarget(Square ep) {
        long newHash = zobristHash;
        // Retirer l'ancien en passant
        if (enPassantTarget != null)
            newHash ^= ZobristHasher.EN_PASSANT_HASH[enPassantTarget.file];
        // Ajouter le nouveau
        if (ep != null)
            newHash ^= ZobristHasher.EN_PASSANT_HASH[ep.file];
        return new BitboardState(bitboards, sideToMove, castlingRights, ep,
                                 halfMoveClock, fullMoveNumber, newHash);
    }

    public BitboardState withHalfMoveClock(int clock) {
        // Le half-move clock n'affecte pas le hash Zobrist (règle des 50 coups)
        return new BitboardState(bitboards, sideToMove, castlingRights, enPassantTarget,
                                 clock, fullMoveNumber, zobristHash);
    }

    public BitboardState withFullMoveNumber(int number) {
        return new BitboardState(bitboards, sideToMove, castlingRights, enPassantTarget,
                                 halfMoveClock, number, zobristHash);
    }

    /** Retourne une copie complète des bitboards (hash conservé). */
    public BitboardState copy() {
        return new BitboardState(copyBitboards(), sideToMove, castlingRights,
                                 enPassantTarget, halfMoveClock, fullMoveNumber, zobristHash);
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
