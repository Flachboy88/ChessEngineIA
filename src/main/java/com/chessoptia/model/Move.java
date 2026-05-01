package com.chessoptia.model;

/**
 * Représente un coup aux échecs.
 * Encodage interne sur un int :
 *   bits  0-5  : case de départ  (0-63)
 *   bits  6-11 : case d'arrivée  (0-63)
 *   bits 12-14 : type de promotion (0=aucune, 1=N, 2=B, 3=R, 4=Q)
 *   bit  16    : flag roque
 *   bit  17    : flag en passant
 * Note : les flags 16 et 17 sont hors de la zone promotion (bits 12-14).
 */
public final class Move {

    // Masques d'extraction
    private static final int FROM_MASK       = 0x3F;
    private static final int TO_MASK         = 0x3F;
    private static final int TO_SHIFT        = 6;
    private static final int PROMO_SHIFT     = 12;
    private static final int PROMO_MASK      = 0x7;
    private static final int FLAG_CASTLING   = 1 << 16;
    private static final int FLAG_EN_PASSANT = 1 << 17;

    /** Valeur nulle (coup invalide). */
    public static final Move NULL = new Move(0);

    private final int encoded;

    private Move(int encoded) {
        this.encoded = encoded;
    }

    // ── Constructeurs statiques ──────────────────────────────────────────────

    /** Coup simple (déplacement ou prise). */
    public static Move of(Square from, Square to) {
        return new Move(from.index | (to.index << TO_SHIFT));
    }

    /** Coup avec promotion. */
    public static Move promotion(Square from, Square to, Piece promoteTo) {
        int promoCode = promoteTo.index + 1; // 1=N,2=B,3=R,4=Q
        return new Move(from.index | (to.index << TO_SHIFT) | (promoCode << PROMO_SHIFT));
    }

    /** Coup en passant. */
    public static Move enPassant(Square from, Square to) {
        return new Move(from.index | (to.index << TO_SHIFT) | FLAG_EN_PASSANT);
    }

    /** Roque. */
    public static Move castling(Square from, Square to) {
        return new Move(from.index | (to.index << TO_SHIFT) | FLAG_CASTLING);
    }

    // ── Accesseurs ──────────────────────────────────────────────────────────

    public Square from() {
        return Square.fromIndex(encoded & FROM_MASK);
    }

    public Square to() {
        return Square.fromIndex((encoded >> TO_SHIFT) & TO_MASK);
    }

    public boolean isPromotion() {
        return ((encoded >> PROMO_SHIFT) & PROMO_MASK) != 0;
    }

    /**
     * Retourne la pièce de promotion, ou null si ce n'est pas un coup de promotion.
     */
    public Piece promotionPiece() {
        int code = (encoded >> PROMO_SHIFT) & PROMO_MASK;
        if (code == 0) return null;
        return Piece.fromIndex(code - 1); // 1=N→0, 2=B→1, 3=R→2, 4=Q→3... en fait index direct
    }

    public boolean isEnPassant() {
        return (encoded & FLAG_EN_PASSANT) != 0;
    }

    public boolean isCastling() {
        return (encoded & FLAG_CASTLING) != 0;
    }

    public int getEncoded() { return encoded; }

    // ── Notation UCI ────────────────────────────────────────────────────────

    /**
     * Retourne la notation UCI du coup (ex: "e2e4", "e7e8q").
     */
    public String toUci() {
        String uci = from().toAlgebraic() + to().toAlgebraic();
        if (isPromotion()) {
            Piece p = promotionPiece();
            uci += (p != null ? p.symbol.toLowerCase() : "");
        }
        return uci;
    }

    /**
     * Parse un coup depuis la notation UCI (ex: "e2e4", "e7e8q").
     */
    public static Move fromUci(String uci) {
        if (uci == null || uci.length() < 4) {
            throw new IllegalArgumentException("Notation UCI invalide : " + uci);
        }
        Square from = Square.fromAlgebraic(uci.substring(0, 2));
        Square to   = Square.fromAlgebraic(uci.substring(2, 4));
        if (uci.length() == 5) {
            Piece promo = Piece.fromFenChar(uci.charAt(4));
            return promotion(from, to, promo);
        }
        return of(from, to);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Move m && m.encoded == this.encoded;
    }

    @Override
    public int hashCode() { return encoded; }

    @Override
    public String toString() { return toUci(); }
}
