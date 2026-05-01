package player;

import game.GameState;
import model.Color;
import model.Move;

/**
 * Interface représentant un joueur (humain ou IA).
 * Toute implémentation doit fournir le prochain coup à jouer.
 */
public interface Player {

    /**
     * Retourne le prochain coup à jouer dans la partie.
     * @param state état courant de la partie
     * @return le coup choisi (doit être légal)
     */
    Move getNextMove(GameState state);

    /**
     * Retourne la couleur du joueur.
     */
    Color getColor();

    /**
     * Retourne le nom d'affichage du joueur.
     */
    String getName();
}
