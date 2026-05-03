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
 * <h2>Recherche par temps (IDDFS)</h2>
 * <p>La recherche utilise l'Iterative Deepening : elle part de la profondeur 1
 * et monte jusqu'à MAX_PLY, en s'arrêtant dès que le budget temps est épuisé.
 * Le résultat retourné est toujours le meilleur coup de la dernière itération complète.</p>
 */
public final class AlphaBetaPlayer extends AIPlayer {

    /** Temps de réflexion par défaut en millisecondes (3 secondes). */
    public static final long DEFAULT_TIME_MS = 3_000L;

    /** Budget temps par coup configuré (millisecondes). */
    private final long timeLimitMs;

    // ── Constructeurs ─────────────────────────────────────────────────────────

    public AlphaBetaPlayer(Color color) {
        this(color, DEFAULT_TIME_MS);
    }

    public AlphaBetaPlayer(Color color, long timeLimitMs) {
        this(color, timeLimitMs, "AlphaBeta (" + (timeLimitMs / 1000) + "s)");
    }

    public AlphaBetaPlayer(Color color, long timeLimitMs, String name) {
        super(color, name);
        if (timeLimitMs < 100) throw new IllegalArgumentException("Le temps doit être >= 100ms");
        this.timeLimitMs = timeLimitMs;
    }

    // ── Logique de sélection ──────────────────────────────────────────────────

    @Override
    protected Move selectMove(GameState state, List<Move> legalMoves) {
        return AlphaBetaSearch.chercherMeilleurCoupTemps(state, timeLimitMs);
    }

    @Override
    public double evaluate(GameState state) {
        return PositionEvaluator.evaluateFor(state.getBitboardState(), color);
    }

    public long getTimeLimitMs() {
        return timeLimitMs;
    }
}
