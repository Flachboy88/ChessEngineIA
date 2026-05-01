package model;

/**
 * Représente une case de l'échiquier.
 * Index 0 = a1 (LSB du bitboard), index 63 = h8 (MSB).
 * Organisation : index = file + rank * 8
 */
public enum Square {
    A1, B1, C1, D1, E1, F1, G1, H1,
    A2, B2, C2, D2, E2, F2, G2, H2,
    A3, B3, C3, D3, E3, F3, G3, H3,
    A4, B4, C4, D4, E4, F4, G4, H4,
    A5, B5, C5, D5, E5, F5, G5, H5,
    A6, B6, C6, D6, E6, F6, G6, H6,
    A7, B7, C7, D7, E7, F7, G7, H7,
    A8, B8, C8, D8, E8, F8, G8, H8;

    /** Index de la case (0-63). */
    public final int index;
    /** Colonne (0=a, 7=h). */
    public final int file;
    /** Rangée (0=1, 7=8). */
    public final int rank;
    /** Masque bitboard de cette case. */
    public final long mask;

    Square() {
        this.index = ordinal();
        this.file = ordinal() % 8;
        this.rank = ordinal() / 8;
        this.mask = 1L << ordinal();
    }

    /** Retourne la case depuis son index (0-63). */
    public static Square fromIndex(int index) {
        return values()[index];
    }

    /**
     * Retourne la case depuis une notation algébrique (ex: "e4").
     * @param notation notation algébrique 2 caractères (lettre + chiffre)
     */
    public static Square fromAlgebraic(String notation) {
        if (notation == null || notation.length() != 2) {
            throw new IllegalArgumentException("Notation invalide : " + notation);
        }
        int file = notation.charAt(0) - 'a';
        int rank = notation.charAt(1) - '1';
        if (file < 0 || file > 7 || rank < 0 || rank > 7) {
            throw new IllegalArgumentException("Notation hors limites : " + notation);
        }
        return fromIndex(file + rank * 8);
    }

    /** Retourne la notation algébrique de la case (ex: "e4"). */
    public String toAlgebraic() {
        return "" + (char)('a' + file) + (char)('1' + rank);
    }

    /** Retourne le nom de la case en minuscule (ex: "e4"). */
    @Override
    public String toString() {
        return toAlgebraic();
    }
}
