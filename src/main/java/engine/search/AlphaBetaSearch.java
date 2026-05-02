package engine.search;

import core.BitboardState;
import engine.evaluation.PositionEvaluator;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
import rules.MoveGenerator;

import java.util.Comparator;
import java.util.List;

/**
 * Algorithme MinMax avec élagage Alpha-Bêta (convention Negamax).
 *
 * <h2>Fonctionnalités implémentées</h2>
 * <ul>
 *   <li><b>Negamax alpha-beta</b> — le score est toujours du point de vue du
 *       joueur courant ; on retourne {@code -alphaBeta(...)} à chaque appel récursif.</li>
 *   <li><b>Quiescence search</b> — à profondeur 0, on continue la recherche sur
 *       les captures pour éviter l'effet horizon (évaluer une position après une
 *       prise incomplète).</li>
 *   <li><b>Move ordering</b> — les coups sont triés avant exploration :
 *       <ol>
 *         <li>Captures MVV-LVA (Most Valuable Victim – Least Valuable Attacker)</li>
 *         <li>Promotions en dame</li>
 *         <li>Autres coups (silencieux)</li>
 *       </ol>
 *       Un bon tri maximise l'élagage beta et peut réduire l'arbre de moitié.
 *   </li>
 * </ul>
 *
 * <h2>Extensions prévues</h2>
 * <ul>
 *   <li><b>Table de transposition</b> — Zobrist hashing pour éviter de recalculer
 *       les positions déjà vues (nécessite d'ajouter un hash dans {@code BitboardState})</li>
 *   <li><b>Iterative deepening</b> — approfondir progressivement pour respecter
 *       une contrainte de temps et utiliser les résultats précédents pour le tri</li>
 *   <li><b>Null move pruning</b> — élaguer les branches clairement perdantes</li>
 *   <li><b>Killer moves & history heuristic</b> — améliorer le tri des coups silencieux</li>
 * </ul>
 */
public final class AlphaBetaSearch {

    /** Score représentant un mat. Dépasse tout score matériel possible. */
    private static final int INF = PositionEvaluator.MATE_SCORE;

    /**
     * Profondeur maximale de la quiescence search (évite les boucles de captures infinies).
     * Une valeur de 6 est suffisante pour capturer toutes les séquences tactiques immédiates.
     */
    private static final int MAX_QUIESCENCE_DEPTH = 6;

    private AlphaBetaSearch() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le meilleur coup trouvé à la profondeur demandée.
     *
     * @param state      état de la partie
     * @param profondeur nombre de demi-coups (plies) à analyser
     * @return meilleur coup selon l'évaluation statique + PST
     * @throws IllegalStateException si aucun coup légal n'est disponible
     */
    public static Move chercherMeilleurCoup(GameState state, int profondeur) {
        BitboardState bs = state.getBitboardState();
        Color side = bs.getSideToMove();

        List<Move> moves = MoveGenerator.generateLegalMoves(bs);
        if (moves.isEmpty()) {
            throw new IllegalStateException("Aucun coup légal — la partie est terminée.");
        }

        // Tri initial au niveau racine (même logique que dans l'arbre)
        sortMoves(moves, bs);

        Move bestMove = moves.get(0);
        int bestScore = -INF;

        for (Move move : moves) {
            BitboardState next = MoveGenerator.applyMove(bs, move);
            int score = -alphaBeta(next, profondeur - 1, -INF, -bestScore, side.opposite());
            if (score > bestScore) {
                bestScore = score;
                bestMove  = move;
            }
        }
        return bestMove;
    }

    // ── Negamax Alpha-Bêta ────────────────────────────────────────────────────

    /**
     * Nœud récursif Alpha-Bêta (negamax).
     *
     * <p>Convention negamax : le score est toujours du point de vue du joueur
     * qui doit jouer à ce nœud. On retourne {@code -alphaBeta(...)} à chaque
     * appel récursif pour inverser la perspective.
     *
     * @param state      état courant du plateau
     * @param profondeur profondeur restante (0 → quiescence)
     * @param alpha      meilleur score garanti pour le joueur courant
     * @param beta       score au-delà duquel l'adversaire évite cette branche
     * @param side       camp qui joue à ce nœud
     * @return score du nœud (positif = avantageux pour {@code side})
     */
    static int alphaBeta(BitboardState state, int profondeur, int alpha, int beta, Color side) {
        // Nœud terminal : quiescence search au lieu d'évaluation statique brute
        if (profondeur == 0) {
            return quiescence(state, alpha, beta, side, MAX_QUIESCENCE_DEPTH);
        }

        List<Move> moves = MoveGenerator.generateLegalMoves(state);

        // Mat ou pat
        if (moves.isEmpty()) {
            if (MoveGenerator.isInCheck(state, side)) {
                // Mat : score d'autant plus mauvais que c'est loin de la racine
                // (favorise le mat le plus rapide)
                return -INF + (1000 - profondeur);
            }
            return 0; // Pat
        }

        sortMoves(moves, state);

        for (Move move : moves) {
            BitboardState next = MoveGenerator.applyMove(state, move);
            int score = -alphaBeta(next, profondeur - 1, -beta, -alpha, side.opposite());

            if (score >= beta) {
                return beta; // Coupure bêta (le nœud est "trop bon" → l'adversaire l'évite)
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    // ── Quiescence Search ─────────────────────────────────────────────────────

    /**
     * Continue la recherche uniquement sur les captures pour éviter l'effet horizon.
     *
     * <p>Sans quiescence, l'IA évaluerait une position en plein milieu d'une
     * séquence de captures, croyant avoir "gagné" une dame alors qu'elle va
     * se faire reprendre au prochain coup. Cette méthode explore toutes les
     * captures disponibles jusqu'à ce que la position soit "calme".
     *
     * <p>On commence par le "stand-pat" : si même sans jouer la position est
     * déjà au-dessus de bêta, on peut élaguer.
     *
     * @param state         état courant
     * @param alpha         borne inférieure
     * @param beta          borne supérieure
     * @param side          camp qui joue
     * @param depthLeft     profondeur quiescence restante (sécurité anti-boucle)
     * @return score de la position "calme"
     */
    static int quiescence(BitboardState state, int alpha, int beta, Color side, int depthLeft) {
        // Score statique ("stand-pat") : si la position est déjà bonne sans capturer, on garde
        int standPat = PositionEvaluator.evaluateFor(state, side);

        if (standPat >= beta) return beta;  // Coupure bêta
        if (standPat > alpha) alpha = standPat;

        if (depthLeft == 0) return alpha;

        // Générer uniquement les captures
        List<Move> captures = generateCaptures(state);
        sortMoves(captures, state);

        for (Move capture : captures) {
            BitboardState next = MoveGenerator.applyMove(state, capture);
            int score = -quiescence(next, -beta, -alpha, side.opposite(), depthLeft - 1);

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    // ── Move Ordering ─────────────────────────────────────────────────────────

    /**
     * Trie les coups pour maximiser l'élagage alpha-bêta.
     *
     * <p>Ordre décroissant de priorité :
     * <ol>
     *   <li>Captures triées par MVV-LVA (capturer une dame avec un pion = excellent)</li>
     *   <li>Promotions en dame</li>
     *   <li>Coups silencieux (score 0)</li>
     * </ol>
     */
    private static void sortMoves(List<Move> moves, BitboardState state) {
        moves.sort(Comparator.comparingInt((Move m) -> moveScore(m, state)).reversed());
    }

    /**
     * Calcule un score heuristique pour le tri des coups.
     * Plus le score est élevé, plus le coup sera exploré en premier.
     */
    private static int moveScore(Move move, BitboardState state) {
        int score = 0;

        // Promotion en dame : très bon coup offensif
        if (move.isPromotion() && move.promotionPiece() == Piece.QUEEN) {
            score += 8000;
        }

        // Capture : MVV-LVA
        // On préfère capturer une pièce de haute valeur avec une pièce de faible valeur
        if (!move.isEnPassant()) {
            BitboardState.PieceOnSquare victim = state.getPieceAt(move.to());
            if (victim != null) {
                BitboardState.PieceOnSquare attacker = state.getPieceAt(move.from());
                int victimValue   = victim.piece().value;
                int attackerValue = attacker != null ? attacker.piece().value : 0;
                // MVV-LVA : valeur victime * 10 - valeur attaquant (pour départager)
                score += victimValue * 10 - attackerValue;
            }
        } else {
            // En passant = capture d'un pion
            score += Piece.PAWN.value * 10 - Piece.PAWN.value;
        }

        return score;
    }

    // ── Génération des captures ───────────────────────────────────────────────

    /**
     * Retourne uniquement les coups légaux qui sont des captures ou des promotions.
     * Utilisé par la quiescence search pour limiter l'explosion combinatoire.
     */
    private static List<Move> generateCaptures(BitboardState state) {
        return MoveGenerator.generateLegalMoves(state).stream()
            .filter(m -> isCapture(m, state))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * Détermine si un coup est une capture (prise normale, en passant, ou promotion avec prise).
     */
    private static boolean isCapture(Move move, BitboardState state) {
        if (move.isEnPassant()) return true;
        if (move.isPromotion()) return true; // les promotions sont toujours intéressantes
        return state.getPieceAt(move.to()) != null;
    }
}
