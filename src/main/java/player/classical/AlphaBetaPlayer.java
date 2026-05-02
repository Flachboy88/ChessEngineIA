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
 * <h2>Paramètres configurables</h2>
 * <ul>
 *   <li>{@code profondeur} — nombre de demi-coups (plies) analysés. La profondeur 4
 *       (valeur par défaut) offre un bon équilibre vitesse / force de jeu.
 *       Augmenter à 5 ou 6 pour une IA plus forte mais plus lente.</li>
 * </ul>
 *
 * <h2>Exemple d'utilisation</h2>
 * <pre>
 *   Player ia = new AlphaBetaPlayer(Color.BLACK);           // profondeur 4
 *   Player ia = new AlphaBetaPlayer(Color.BLACK, 5);        // profondeur 5
 *   Player ia = new AlphaBetaPlayer(Color.WHITE, 3, "Easy");// profondeur 3, nom custom
 * </pre>
 */
public final class AlphaBetaPlayer extends AIPlayer {

    /** Profondeur de recherche par défaut (plies). */
    public static final int DEFAULT_DEPTH = 4;

    /** Profondeur de recherche utilisée par cette instance. */
    private final int profondeur;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    /**
     * Crée un joueur AlphaBeta avec la profondeur par défaut ({@value DEFAULT_DEPTH}).
     * @param color couleur jouée par cette IA
     */
    public AlphaBetaPlayer(Color color) {
        this(color, DEFAULT_DEPTH);
    }

    /**
     * Crée un joueur AlphaBeta avec une profondeur personnalisée.
     * @param color      couleur jouée par cette IA
     * @param profondeur nombre de demi-coups à analyser (recommandé : 3-6)
     */
    public AlphaBetaPlayer(Color color, int profondeur) {
        this(color, profondeur, "AlphaBeta (depth=" + profondeur + ")");
    }

    /**
     * Crée un joueur AlphaBeta avec profondeur et nom personnalisés.
     * @param color      couleur jouée par cette IA
     * @param profondeur nombre de demi-coups à analyser
     * @param name       nom affiché dans l'interface
     */
    public AlphaBetaPlayer(Color color, int profondeur, String name) {
        super(color, name);
        if (profondeur < 1) throw new IllegalArgumentException("La profondeur doit être >= 1");
        this.profondeur = profondeur;
    }

    // ── Logique de sélection ──────────────────────────────────────────────────

    /**
     * Sélectionne le meilleur coup via l'algorithme Alpha-Bêta.
     * La liste {@code legalMoves} est fournie par {@link AIPlayer#getNextMove}
     * mais on laisse {@link AlphaBetaSearch} regénérer depuis l'état complet
     * pour avoir accès aux bitboards nécessaires au tri MVV-LVA.
     */
    @Override
    protected Move selectMove(GameState state, List<Move> legalMoves) {
        return AlphaBetaSearch.chercherMeilleurCoup(state, profondeur);
    }

    /**
     * Évaluation statique de la position du point de vue de ce joueur.
     * Exposée pour les tests et les affichages de debug.
     *
     * @param state état courant
     * @return score positif si la position est favorable à ce joueur
     */
    @Override
    public double evaluate(GameState state) {
        return PositionEvaluator.evaluateFor(state.getBitboardState(), color);
    }

    // ── Accesseurs ────────────────────────────────────────────────────────────

    /** Retourne la profondeur de recherche configurée. */
    public int getProfondeur() {
        return profondeur;
    }
}
