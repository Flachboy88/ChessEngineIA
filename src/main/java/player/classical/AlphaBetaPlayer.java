package player.classical;

import engine.book.OpeningBook;
import engine.evaluation.PositionEvaluator;
import engine.search.AlphaBetaSearch;
import engine.search.TimeManager;
import engine.tb.SyzygyTablebase;
import game.GameState;
import model.Color;
import model.Move;
import player.AIPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Joueur IA basé sur l'algorithme MinMax avec élagage Alpha-Bêta.
 *
 * <p>Délègue entièrement la recherche à {@link AlphaBetaSearch} et
 * l'évaluation statique à {@link PositionEvaluator} (matériel + PST + phase).
 *
 * <h2>Nouveautés (v2)</h2>
 * <ul>
 *   <li><b>Livre d'ouvertures Polyglot</b> — si configuré, les positions couvertes
 *       sont jouées instantanément depuis le livre, sans calcul.</li>
 *   <li><b>Time Manager dynamique</b> — si {@code timeRemaining} est fourni,
 *       le budget est calculé dynamiquement au lieu d'un temps fixe.</li>
 *   <li><b>Tablebases Syzygy</b> — si configurées, les finales sont jouées
 *       parfaitement sans calcul.</li>
 * </ul>
 *
 * <h2>Modes d'utilisation</h2>
 * <pre>
 * // Mode basique (identique à v1) :
 * new AlphaBetaPlayer(Color.WHITE, 3_000L)
 *
 * // Mode avec livre d'ouvertures :
 * new AlphaBetaPlayer(Color.WHITE, 3_000L)
 *     .withOpeningBook(Path.of("books/performance.bin"))
 *
 * // Mode complet (livre + tablebases + time management pendule) :
 * AlphaBetaPlayer ia = new AlphaBetaPlayer(Color.WHITE, 180_000L)  // 3 min
 *     .withOpeningBook(Path.of("books/performance.bin"))
 *     .withTablebases(Path.of("syzygy/"))
 *     .withClockMode(true);  // active le time management dynamique
 * </pre>
 *
 * <h2>Recherche par temps (IDDFS)</h2>
 * La recherche utilise l'Iterative Deepening : elle part de la profondeur 1
 * et monte jusqu'à MAX_PLY, en s'arrêtant dès que le budget temps est épuisé.
 * Le résultat retourné est toujours le meilleur coup de la dernière itération complète.
 */
public final class AlphaBetaPlayer extends AIPlayer {

    private static final Logger LOG = Logger.getLogger(AlphaBetaPlayer.class.getName());

    /** Temps de réflexion par défaut en millisecondes (3 secondes). */
    public static final long DEFAULT_TIME_MS = 3_000L;

    /** Budget temps par coup configuré (millisecondes). */
    private final long timeLimitMs;

    // ── Composants optionnels ─────────────────────────────────────────────────

    /** Livre d'ouvertures Polyglot (null si désactivé). */
    private OpeningBook openingBook = null;

    /** Tablebases Syzygy (désactivées par défaut). */
    private SyzygyTablebase tablebase = SyzygyTablebase.disabled();

    /**
     * Mode pendule : si true, le timeLimitMs est interprété comme le temps
     * RESTANT sur la pendule (pas un budget fixe par coup).
     * Le TimeManager calcule alors un budget dynamique adapté.
     */
    private boolean clockMode = false;

    /** Incrément par coup en mode pendule (ms). */
    private long incrementMs = 0L;

    /**
     * Numéro du coup actuel (utilisé pour le time management en ouverture).
     * Incrémenté automatiquement à chaque coup joué.
     */
    private int moveNumber = 1;

    /** True si le coup précédent venait du livre (pour bonus post-livre). */
    private boolean prevMoveFromBook = false;

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

    // ── Builders (fluent API) ─────────────────────────────────────────────────

    /**
     * Active le livre d'ouvertures depuis un fichier Polyglot (.bin).
     * Si le fichier est introuvable, un warning est loggué et le livre est ignoré.
     *
     * @param bookPath chemin vers le fichier .bin
     * @return this (fluent)
     */
    public AlphaBetaPlayer withOpeningBook(Path bookPath) {
        try {
            this.openingBook = new OpeningBook(bookPath);
            LOG.info("Livre d'ouvertures activé : " + bookPath);
        } catch (Exception e) {
            LOG.warning("Impossible de charger le livre d'ouvertures : " + e.getMessage());
        }
        return this;
    }

    /** Injecte un livre déjà chargé (utile pour les tests). */
    public AlphaBetaPlayer withOpeningBook(OpeningBook book) {
        this.openingBook = book;
        return this;
    }

    /**
     * Active les tablebases Syzygy depuis un répertoire.
     * Si le répertoire est vide ou inexistant, les tablebases sont silencieusement désactivées.
     *
     * @param tbDir répertoire contenant les fichiers .rtbw / .rtbz
     * @return this (fluent)
     */
    public AlphaBetaPlayer withTablebases(Path tbDir) {
        this.tablebase = new SyzygyTablebase(tbDir);
        return this;
    }

    /** Injecte une instance de tablebase déjà construite (utile pour les tests). */
    public AlphaBetaPlayer withTablebases(SyzygyTablebase tb) {
        this.tablebase = tb;
        return this;
    }

    /**
     * Active le mode pendule (time management dynamique).
     *
     * @param clockMode   true = timeLimitMs est le temps restant sur la pendule
     * @param incrementMs incrément par coup (ms), 0 si aucun
     * @return this (fluent)
     */
    public AlphaBetaPlayer withClockMode(boolean clockMode, long incrementMs) {
        this.clockMode   = clockMode;
        this.incrementMs = incrementMs;
        return this;
    }

    /** Active le mode pendule sans incrément. */
    public AlphaBetaPlayer withClockMode(boolean clockMode) {
        return withClockMode(clockMode, 0L);
    }

    // ── Logique de sélection ──────────────────────────────────────────────────

    @Override
    protected Move selectMove(GameState state, List<Move> legalMoves) {
        // ── 1. Livre d'ouvertures ──────────────────────────────────────────────
        if (openingBook != null) {
            Optional<Move> bookMove = openingBook.probe(state.getBitboardState());
            if (bookMove.isPresent() && legalMoves.contains(bookMove.get())) {
                Move m = bookMove.get();
                LOG.fine("Livre d'ouvertures → " + m.toUci());
                prevMoveFromBook = true;
                moveNumber++;
                return m;
            }
        }

        boolean justLeftBook = prevMoveFromBook;
        prevMoveFromBook = false;

        // ── 2. Tablebases ──────────────────────────────────────────────────────
        if (tablebase.isAvailable() && tablebase.canProbe(state.getBitboardState())) {
            Move tbMove = probeTablebases(state, legalMoves);
            if (tbMove != null) {
                LOG.fine("Tablebase → " + tbMove.toUci());
                moveNumber++;
                return tbMove;
            }
        }

        // ── 3. Time management ─────────────────────────────────────────────────
        long budget;
        if (clockMode) {
            TimeManager tm = new TimeManager(timeLimitMs, incrementMs, moveNumber, justLeftBook);
            budget = tm.getTargetMs();
        } else {
            budget = timeLimitMs;
        }

        moveNumber++;

        // ── 4. Recherche alpha-bêta ────────────────────────────────────────────
        return AlphaBetaSearch.chercherMeilleurCoupTemps(state, budget);
    }

    // ── Sonde des tablebases pour la sélection de coup ────────────────────────

    /**
     * Cherche le meilleur coup parmi les legaux en sondant les tablebases.
     * On joue le coup qui amène l'adversaire dans la position la plus défavorable (WDL minimal).
     * En cas d'égalité WDL, on préfère le DTZ le plus faible (convertir plus vite).
     */
    private Move probeTablebases(GameState state, List<Move> legalMoves) {
        Move bestMove   = null;
        int  bestWdl    = Integer.MIN_VALUE;
        int  bestDtz    = Integer.MAX_VALUE;

        for (Move move : legalMoves) {
            // Simuler le coup (sans GameState complet, utiliser applyMove)
            var nextState = rules.MoveGenerator.applyMove(state.getBitboardState(), move);
            var probe = tablebase.probe(nextState);
            if (!probe.isKnown()) return null; // position hors couverture → laisser l'IA

            // Du point de vue de l'adversaire (le camp qui vient de recevoir le coup)
            // Un WDL adversaire faible = bon pour nous.
            int opponentWdl = -probe.wdl;
            int dtz          = probe.dtz;

            if (opponentWdl > bestWdl || (opponentWdl == bestWdl && dtz < bestDtz)) {
                bestWdl  = opponentWdl;
                bestDtz  = dtz;
                bestMove = move;
            }
        }
        return bestMove;
    }

    // ── Accesseurs ────────────────────────────────────────────────────────────

    @Override
    public double evaluate(GameState state) {
        return PositionEvaluator.evaluateFor(state.getBitboardState(), color);
    }

    public long getTimeLimitMs()          { return timeLimitMs; }
    public boolean hasOpeningBook()       { return openingBook != null; }
    public boolean hasTablebases()        { return tablebase.isAvailable(); }
    public SyzygyTablebase getTablebase() { return tablebase; }
}
