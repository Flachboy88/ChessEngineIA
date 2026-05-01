package player;

import game.GameState;
import model.Color;
import model.Move;

/**
 * Joueur humain.
 * Le coup est fourni de l'extérieur via setNextMove() avant que getNextMove() soit appelé.
 * Dans une interface graphique, le coup est posé par l'utilisateur via l'API.
 */
public final class HumanPlayer implements Player {

    private final Color color;
    private final String name;
    private Move pendingMove = null;

    public HumanPlayer(Color color, String name) {
        this.color = color;
        this.name  = name;
    }

    /**
     * Définit le coup que ce joueur va jouer au prochain appel de getNextMove().
     * @param move coup légal choisi par l'utilisateur
     */
    public void setNextMove(Move move) {
        this.pendingMove = move;
    }

    /**
     * Retourne le coup défini par setNextMove().
     * @throws IllegalStateException si aucun coup n'a été défini
     */
    @Override
    public Move getNextMove(GameState state) {
        if (pendingMove == null) {
            throw new IllegalStateException(
                "Aucun coup défini pour " + name + ". Appelez setNextMove() d'abord.");
        }
        Move move = pendingMove;
        pendingMove = null;
        return move;
    }

    @Override public Color getColor() { return color; }
    @Override public String getName()  { return name; }
}
