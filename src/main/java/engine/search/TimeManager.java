package engine.search;

/**
 * Gestionnaire de temps dynamique pour l'IA.
 *
 * <h2>Problème résolu</h2>
 * Avec un temps fixe par coup (ex: 3 s), on perd du temps en positions simples
 * et on manque de temps dans les positions critiques. Le time manager calcule
 * un budget adapté à chaque situation.
 *
 * <h2>Modèle de base</h2>
 * On estime qu'il reste environ {@value #EXPECTED_MOVES_LEFT} coups à jouer.
 * Le budget de base est :
 * <pre>
 *   base = tempsRestant / EXPECTED_MOVES_LEFT + increment * 0.8
 * </pre>
 *
 * <h2>Facteurs d'ajustement</h2>
 * <ul>
 *   <li><b>Instabilité du meilleur coup</b> : si le coup change entre les itérations,
 *       on accorde 1.5× plus de temps.</li>
 *   <li><b>Phase de partie</b> : en ouverture (coups 1-10), on joue plus vite (0.7×)
 *       car les positions sont moins critiques et le livre d'ouvertures est censé
 *       couvrir beaucoup de positions.</li>
 *   <li><b>Fin de livre</b> : si on vient de quitter le livre, petit bonus (1.2×).</li>
 *   <li><b>Temps minimal</b> : jamais moins de {@value #MIN_TIME_MS} ms pour éviter
 *       de jouer un coup de profondeur 1.</li>
 *   <li><b>Temps maximal</b> : jamais plus de {@code tempsRestant / 3} pour garder
 *       une réserve confortable.</li>
 * </ul>
 *
 * <h2>Intégration avec AlphaBetaSearch</h2>
 * {@link AlphaBetaSearch} appelle {@link #shouldStop(int, Move, Move)} à la fin
 * de chaque itération IDDFS pour décider s'il faut s'arrêter ou continuer.
 * La condition de stop est :
 * <pre>
 *   tempsÉcoulé >= targetTime
 *   ET (profondeur >= 4 OU coup stable depuis 2 itérations)
 * </pre>
 */
public final class TimeManager {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Nombre de coups restants estimés pour le calcul du budget. */
    private static final int EXPECTED_MOVES_LEFT = 30;

    /** Temps minimum garanti par coup (ms). */
    private static final int MIN_TIME_MS = 50;

    /** Facteur d'utilisation de l'incrément (on garde 20% de marge). */
    private static final double INCREMENT_FACTOR = 0.8;

    /** Multiplicateur si le meilleur coup est instable entre itérations. */
    private static final double INSTABILITY_BONUS = 1.5;

    /** Multiplicateur en début de partie (coups 1-10). */
    private static final double OPENING_FACTOR = 0.7;

    /** Multiplicateur si on vient de quitter le livre. */
    private static final double POST_BOOK_BONUS = 1.2;

    /** Profondeur minimale avant arrêt anticipé. */
    private static final int MIN_DEPTH_TO_STOP = 4;

    // ── État ──────────────────────────────────────────────────────────────────

    private final long startTime;
    private final long targetTime;     // temps cible calculé
    private final long hardDeadline;   // limite absolue (ne jamais dépasser)

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Construit un TimeManager pour une configuration donnée.
     *
     * @param timeRemainingMs  temps restant sur la pendule (ms)
     * @param incrementMs      incrément par coup (ms), 0 si aucun
     * @param moveNumber       numéro du coup actuel (1-based)
     * @param justLeftBook     true si on vient de quitter le livre d'ouvertures
     */
    public TimeManager(long timeRemainingMs, long incrementMs, int moveNumber,
                       boolean justLeftBook) {
        this.startTime = System.currentTimeMillis();

        // Budget de base
        long base = timeRemainingMs / EXPECTED_MOVES_LEFT
                  + (long)(incrementMs * INCREMENT_FACTOR);

        // Facteurs multiplicateurs
        double factor = 1.0;
        if (moveNumber <= 10) factor *= OPENING_FACTOR;
        if (justLeftBook)     factor *= POST_BOOK_BONUS;

        long target = (long)(base * factor);

        // Bornes
        target = Math.max(target, MIN_TIME_MS);
        target = Math.min(target, timeRemainingMs / 3);

        this.targetTime   = target;
        this.hardDeadline = startTime + Math.min(timeRemainingMs - 100L,
                                                  timeRemainingMs * 2 / 3);
    }

    /**
     * Constructeur simplifié pour budget temps fixe (mode non-pendule).
     * Utilisé quand on joue avec un temps fixe par coup (ex: 3 s par coup).
     *
     * @param timeLimitMs  budget total pour ce coup (ms)
     */
    public static TimeManager fixedTime(long timeLimitMs) {
        return new TimeManager(timeLimitMs * EXPECTED_MOVES_LEFT, 0, 1, false) {
            @Override public long getDeadline() {
                return System.currentTimeMillis() - /* startTime */ 0 + timeLimitMs;
            }
        };
        // Note : cette surcharge interne est approximative.
        // Pour le mode fixedTime, utiliser directement la deadline ci-dessous.
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne la deadline absolue à passer à {@link AlphaBetaSearch}.
     * Le moteur s'arrête dès que {@code System.currentTimeMillis() >= deadline}.
     */
    public long getDeadline() {
        return startTime + targetTime;
    }

    /**
     * Retourne la deadline dure (ne jamais dépasser, quelle que soit la situation).
     */
    public long getHardDeadline() {
        return hardDeadline;
    }

    /**
     * Décide si la recherche doit s'arrêter après l'itération IDDFS courante.
     *
     * @param depth        profondeur venant d'être complétée
     * @param previousBest meilleur coup de l'itération précédente (null si depth=1)
     * @param currentBest  meilleur coup de l'itération courante
     * @return true si on doit s'arrêter
     */
    public boolean shouldStop(int depth, model.Move previousBest, model.Move currentBest) {
        long elapsed = System.currentTimeMillis() - startTime;

        // Toujours s'arrêter si deadline dure dépassée
        if (elapsed >= (hardDeadline - startTime)) return true;

        // Pas d'arrêt prématuré avant la profondeur minimale
        if (depth < MIN_DEPTH_TO_STOP) return false;

        // Instabilité du meilleur coup → prolonger
        boolean unstable = previousBest != null && currentBest != null
                        && !previousBest.equals(currentBest);

        long effectiveTarget = unstable
                ? (long)(targetTime * INSTABILITY_BONUS)
                : targetTime;

        // Heuristique : si moins de 70% du target restant, pas la peine de commencer
        // une nouvelle itération qui prendrait probablement plus de temps
        long remaining = effectiveTarget - elapsed;
        if (remaining < effectiveTarget * 0.3) return true;

        return elapsed >= effectiveTarget;
    }

    /** Temps écoulé depuis le début de la recherche (ms). */
    public long elapsedMs() {
        return System.currentTimeMillis() - startTime;
    }

    /** Retourne le temps cible calculé (ms), utile pour les logs et tests. */
    public long getTargetMs() { return targetTime; }
}
