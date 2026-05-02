package player.classical;

import game.GameState;
import model.Color;
import model.Move;
import player.AIPlayer;

import java.util.List;
import java.util.Random;

/**
 * IA basique qui choisit un coup au hasard parmi les coups légaux.
 * Utile pour les tests et comme adversaire de référence minimal.
 */
public final class RandomAIPlayer extends AIPlayer {

    private final Random random;

    public RandomAIPlayer(Color color) {
        this(color, "Random AI", new Random());
    }

    public RandomAIPlayer(Color color, String name, Random random) {
        super(color, name);
        this.random = random;
    }

    @Override
    protected Move selectMove(GameState state, List<Move> legalMoves) {
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }
}
