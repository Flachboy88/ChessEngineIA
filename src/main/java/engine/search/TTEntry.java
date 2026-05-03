package engine.search;

/**
 * Entrée de la table de transposition.
 *
 * <p>Séparée dans son propre fichier pour éviter les problèmes d'accès
 * à la classe imbriquée depuis {@link AlphaBetaSearch} (visibilité javac/IDE).
 *
 * <p>Les champs sont publics et mutables intentionnellement : la table est
 * un tableau plat de records réutilisables pour éviter toute allocation GC.
 */
public final class TTEntry {

    /** Hash Zobrist complet de la position (validation anti-collision). */
    public long hash = 0L;

    /** Score évalué à cette profondeur (en centipions, convention negamax). */
    public int score = 0;

    /**
     * Profondeur à laquelle le score a été calculé.
     * -1 = entrée vide (jamais utilisée).
     */
    public int depth = -1;

    /**
     * Type du nœud :
     * <ul>
     *   <li>{@link TranspositionTable#EXACT} — score exact</li>
     *   <li>{@link TranspositionTable#LOWER} — borne inférieure (coupure bêta)</li>
     *   <li>{@link TranspositionTable#UPPER} — borne supérieure (sous alpha)</li>
     * </ul>
     */
    public byte type = TranspositionTable.EXACT;

    /**
     * Meilleur coup trouvé à ce nœud, encodé en int via {@code Move.getEncoded()}.
     * 0 = aucun coup connu.
     */
    public int bestMoveEncoded = 0;
}
