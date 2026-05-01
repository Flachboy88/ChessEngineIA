package com.chessoptia.model;

/**
 * Types de pièces aux échecs.
 * L'index (0-5) est utilisé pour indexer les bitboards.
 */
public enum Piece {
    PAWN(0, "P", 100),
    KNIGHT(1, "N", 320),
    BISHOP(2, "B", 330),
    ROOK(3, "R", 500),
    QUEEN(4, "Q", 900),
    KING(5, "K", 20000);

    /** Index dans le tableau de bitboards (0-5). */
    public final int index;
    /** Symbole FEN (majuscule = blanc). */
    public final String symbol;
    /** Valeur matérielle de base (pour évaluation). */
    public final int value;

    Piece(int index, String symbol, int value) {
        this.index = index;
        this.symbol = symbol;
        this.value = value;
    }

    /** Retourne le symbole FEN selon la couleur. */
    public String fenChar(Color color) {
        return color == Color.WHITE ? symbol : symbol.toLowerCase();
    }

    /** Retourne la pièce correspondant à l'index bitboard (0-5). */
    public static Piece fromIndex(int index) {
        return values()[index];
    }

    /** Retourne la pièce correspondant au caractère FEN. */
    public static Piece fromFenChar(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'P' -> PAWN;
            case 'N' -> KNIGHT;
            case 'B' -> BISHOP;
            case 'R' -> ROOK;
            case 'Q' -> QUEEN;
            case 'K' -> KING;
            default -> throw new IllegalArgumentException("Caractère FEN invalide : " + c);
        };
    }
}
