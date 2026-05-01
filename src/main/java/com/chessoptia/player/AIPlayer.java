package com.chessoptia.player;

import com.chessoptia.game.GameState;
import com.chessoptia.model.Color;
import com.chessoptia.model.Move;

import java.util.List;

/**
 * Classe abstraite de base pour les joueurs IA.
 * Les sous-classes implémentent la stratégie de sélection du coup.
 *
 * Prévu pour le deep learning : surcharger evaluate() et search() dans les futures IA.
 */
public abstract class AIPlayer implements Player {

    protected final Color color;
    protected final String name;

    protected AIPlayer(Color color, String name) {
        this.color = color;
        this.name  = name;
    }

    /**
     * Sélectionne le meilleur coup depuis la liste des coups légaux.
     * À surcharger dans les implémentations concrètes.
     *
     * @param state      état courant
     * @param legalMoves liste des coups légaux (non vide)
     * @return le coup choisi
     */
    protected abstract Move selectMove(GameState state, List<Move> legalMoves);

    /**
     * Évalue la position courante du point de vue du camp de l'IA.
     * Retourne un score positif si la position est favorable à l'IA.
     * Par convention : +infini = victoire, -infini = défaite.
     *
     * À surcharger pour les IA avancées / réseaux de neurones.
     */
    public double evaluate(GameState state) {
        return 0.0; // neutre par défaut
    }

    @Override
    public Move getNextMove(GameState state) {
        List<Move> legal = state.getLegalMoves();
        if (legal.isEmpty()) throw new IllegalStateException("Aucun coup légal disponible.");
        return selectMove(state, legal);
    }

    @Override public Color getColor() { return color; }
    @Override public String getName()  { return name; }
}
