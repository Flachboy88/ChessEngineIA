package player.classical;

import engine.evaluation.PositionEvaluator;
import engine.search.AlphaBetaSearch;
import game.GameState;
import model.Color;
import model.Move;
import player.AIPlayer;

import java.util.List;

/**
 * Joueur IA basé sur l'algorithme MinMax avec élagage Alpha-Bêta.
 *
 * <p>Délègue entièrement la recherche à {@link AlphaBetaSearch} et
 * l'évaluation statique à {@link PositionEvaluator} (matériel + PST + phase).
 *
 * <h2>Profondeur adaptative en fin de partie</h2>
 * <p>Quand chaque camp a au plus {@value ENDGAME_PIECE_THRESHOLD} pièces,
 * la profondeur est augmentée de {@value ENDGAME_DEPTH_BONUS} demi-coups.
 * L'arbre de jeu est beaucoup plus petit en fin de partie, donc le surcoût est limité
 * et la précision de calcul s'améliore significativement.</p>
 */
public final class AlphaBetaPlayer extends AIPlayer {

    /** Profondeur de recherche par défaut (plies). */
    public static final int DEFAULT_DEPTH = 4;

    /**
     * Seuil de pièces par camp en dessous duquel on active la profondeur bonus.
     * 7 pièces par camp = fin de partie (tour + dame + quelques pions typiquement).
     */
    private static final int ENDGAME_PIECE_THRESHOLD = 7;

    /**
     * Bonus de profondeur en fin de partie.
     * +2 demi-coups : bon compromis perf/précision (depth 4 → 6, depth 5 → 7).
     */
    private static final int ENDGAME_DEPTH_BONUS = 2;

    /** Profondeur de recherche de base configurée. */
    private final int profondeur;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    public AlphaBetaPlayer(Color color) {
        this(color, DEFAULT_DEPTH);
    }

    public AlphaBetaPlayer(Color color, int profondeur) {
        this(color, profondeur, "AlphaBeta (depth=" + profondeur + ")");
    }

    public AlphaBetaPlayer(Color color, int profondeur, String name) {
        super(color, name);
        if (profondeur < 1) throw new IllegalArgumentException("La profondeur doit être >= 1");
        this.profondeur = profondeur;
    }

    // ── Logique de sélection ──────────────────────────────────────────────────

    @Override
    protected Move selectMove(GameState state, List<Move> legalMoves) {
        int depthEffective = profondeur;

        // Profondeur adaptative : augmenter en fin de partie
        if (state.getPieceCount(Color.WHITE) <= ENDGAME_PIECE_THRESHOLD
                && state.getPieceCount(Color.BLACK) <= ENDGAME_PIECE_THRESHOLD) {
            depthEffective = profondeur + ENDGAME_DEPTH_BONUS;
        }

        return AlphaBetaSearch.chercherMeilleurCoup(state, depthEffective);
    }

    @Override
    public double evaluate(GameState state) {
        return PositionEvaluator.evaluateFor(state.getBitboardState(), color);
    }

    public int getProfondeur() {
        return profondeur;
    }
}
