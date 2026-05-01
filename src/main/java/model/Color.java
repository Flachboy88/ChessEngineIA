package model;

/**
 * Représente la couleur d'un joueur ou d'une pièce.
 */
public enum Color {
    WHITE,
    BLACK;

    /** Retourne la couleur opposée. */
    public Color opposite() {
        return this == WHITE ? BLACK : WHITE;
    }

    /** Retourne 1 pour WHITE, -1 pour BLACK (utile pour les évaluations DL). */
    public int sign() {
        return this == WHITE ? 1 : -1;
    }
}
