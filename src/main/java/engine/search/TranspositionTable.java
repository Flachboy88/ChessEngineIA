package engine.search;

/**
 * Table de transposition basée sur le hachage Zobrist.
 *
 * <p>Évite de recalculer des positions déjà analysées, même si on y arrive
 * par des chemins différents (transpositions). C'est l'optimisation la plus
 * impactante pour un moteur alpha-bêta après le move ordering.
 *
 * <h2>Structure</h2>
 * Tableau plat de {@link TTEntry} indexé par {@code hash & mask}.
 * Chaque entrée contient : hash complet, score, profondeur, type de nœud,
 * meilleur coup encodé.
 *
 * <h2>Stratégie de remplacement</h2>
 * On remplace si l'entrée est vide, si la position est différente (hash ≠),
 * ou si la nouvelle profondeur est supérieure ou égale (depth-preferred).
 *
 * <h2>Taille</h2>
 * Puissance de 2 → masque AND plus rapide qu'un modulo.
 * Défaut {@value DEFAULT_SIZE_BITS} bits = 2^22 ≈ 4M entrées ≈ 160 Mo.
 * Réduire à 20 (≈40 Mo) si la mémoire est contrainte.
 */
public final class TranspositionTable {

    // ── Types de nœuds ────────────────────────────────────────────────────────

    /** Tous les coups ont été explorés — score exact. */
    public static final byte EXACT = 0;
    /** Coupure bêta : le score réel est ≥ entry.score (lower bound). */
    public static final byte LOWER = 1;
    /** Aucun coup n'a dépassé alpha : le score réel est ≤ entry.score (upper bound). */
    public static final byte UPPER = 2;

    // ── Table ─────────────────────────────────────────────────────────────────

    private final TTEntry[] table;
    private final int       mask;

    private static final int DEFAULT_SIZE_BITS = 22;

    public TranspositionTable() {
        this(DEFAULT_SIZE_BITS);
    }

    /**
     * @param sizeBits log₂ de la taille (ex: 20 → 1M entrées ≈ 40 Mo)
     */
    public TranspositionTable(int sizeBits) {
        int size  = 1 << sizeBits;
        this.mask  = size - 1;
        this.table = new TTEntry[size];
        for (int i = 0; i < size; i++) table[i] = new TTEntry();
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Récupère l'entrée pour ce hash, ou {@code null} si absente ou collision.
     * La vérification du hash complet protège contre les faux positifs d'index.
     */
    public TTEntry get(long hash) {
        TTEntry e = table[(int)(hash & mask)];
        return (e.depth >= 0 && e.hash == hash) ? e : null;
    }

    /**
     * Stocke un résultat dans la table.
     *
     * @param hash            hash Zobrist de la position
     * @param score           score évalué (centipions, negamax)
     * @param depth           profondeur de calcul
     * @param type            {@link #EXACT}, {@link #LOWER} ou {@link #UPPER}
     * @param bestMoveEncoded meilleur coup encodé ({@code Move.getEncoded()}), 0 si inconnu
     */
    public void store(long hash, int score, int depth, byte type, int bestMoveEncoded) {
        TTEntry e = table[(int)(hash & mask)];
        if (e.depth < 0 || depth >= e.depth || e.hash != hash) {
            e.hash            = hash;
            e.score           = score;
            e.depth           = depth;
            e.type            = type;
            e.bestMoveEncoded = bestMoveEncoded;
        }
    }

    /** Vide la table — à appeler entre deux parties. */
    public void clear() {
        for (TTEntry e : table) e.depth = -1;
    }
}
