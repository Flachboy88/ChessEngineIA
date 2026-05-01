package model;

/**
 * Droits de roque encodés sur 4 bits.
 * Bit 0 = petit roque blanc (K), Bit 1 = grand roque blanc (Q),
 * Bit 2 = petit roque noir (k), Bit 3 = grand roque noir (q).
 */
public final class CastlingRights {

    public static final int WHITE_KINGSIDE  = 0b0001;
    public static final int WHITE_QUEENSIDE = 0b0010;
    public static final int BLACK_KINGSIDE  = 0b0100;
    public static final int BLACK_QUEENSIDE = 0b1000;
    public static final int ALL             = 0b1111;
    public static final int NONE            = 0b0000;

    private final int rights;

    public CastlingRights(int rights) {
        this.rights = rights & ALL;
    }

    public static CastlingRights all()  { return new CastlingRights(ALL); }
    public static CastlingRights none() { return new CastlingRights(NONE); }

    public boolean canWhiteKingside()  { return (rights & WHITE_KINGSIDE)  != 0; }
    public boolean canWhiteQueenside() { return (rights & WHITE_QUEENSIDE) != 0; }
    public boolean canBlackKingside()  { return (rights & BLACK_KINGSIDE)  != 0; }
    public boolean canBlackQueenside() { return (rights & BLACK_QUEENSIDE) != 0; }

    public boolean canKingside(Color color) {
        return color == Color.WHITE ? canWhiteKingside() : canBlackKingside();
    }

    public boolean canQueenside(Color color) {
        return color == Color.WHITE ? canWhiteQueenside() : canBlackQueenside();
    }

    /** Retourne de nouveaux droits avec certains bits retirés. */
    public CastlingRights remove(int mask) {
        return new CastlingRights(rights & ~mask);
    }

    public int getRights() { return rights; }

    /** Encode en notation FEN (ex: "KQkq", "-"). */
    public String toFen() {
        if (rights == NONE) return "-";
        StringBuilder sb = new StringBuilder();
        if (canWhiteKingside())  sb.append('K');
        if (canWhiteQueenside()) sb.append('Q');
        if (canBlackKingside())  sb.append('k');
        if (canBlackQueenside()) sb.append('q');
        return sb.toString();
    }

    /** Parse depuis la notation FEN. */
    public static CastlingRights fromFen(String fen) {
        if ("-".equals(fen)) return none();
        int r = NONE;
        for (char c : fen.toCharArray()) {
            switch (c) {
                case 'K' -> r |= WHITE_KINGSIDE;
                case 'Q' -> r |= WHITE_QUEENSIDE;
                case 'k' -> r |= BLACK_KINGSIDE;
                case 'q' -> r |= BLACK_QUEENSIDE;
                default  -> throw new IllegalArgumentException("Droit de roque invalide : " + c);
            }
        }
        return new CastlingRights(r);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CastlingRights cr && cr.rights == this.rights;
    }

    @Override
    public int hashCode() { return rights; }

    @Override
    public String toString() { return toFen(); }
}
