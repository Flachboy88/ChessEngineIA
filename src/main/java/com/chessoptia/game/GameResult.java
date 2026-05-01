package com.chessoptia.game;

/**
 * Résultat possible d'une partie d'échecs.
 */
public enum GameResult {
    IN_PROGRESS("Partie en cours"),
    WHITE_WINS("Les blancs gagnent (échec et mat)"),
    BLACK_WINS("Les noirs gagnent (échec et mat)"),
    STALEMATE("Pat — nulle"),
    DRAW_50_MOVES("Nulle — règle des 50 coups"),
    DRAW_INSUFFICIENT_MATERIAL("Nulle — matériel insuffisant"),
    DRAW_REPETITION("Nulle — répétition triple");

    public final String description;

    GameResult(String description) {
        this.description = description;
    }

    public boolean isOver() {
        return this != IN_PROGRESS;
    }
}
