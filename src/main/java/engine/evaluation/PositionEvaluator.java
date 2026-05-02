package engine.evaluation;

import core.BitboardState;
import model.Color;
import model.Piece;

/**
 * Évaluateur statique de position — cœur de l'IA classique MinMax/AlphaBeta.
 *
 * <p>Retourne un score en centipions du point de vue des Blancs :
 * <ul>
 *   <li>Score positif  → position favorable aux Blancs</li>
 *   <li>Score négatif  → position favorable aux Noirs</li>
 *   <li>+∞ / −∞       → mat</li>
 * </ul>
 *
 * <h2>Composantes implémentées</h2>
 * <ol>
 *   <li><b>Matériel</b> — somme des valeurs de pièces (P=100, N=320, B=330, R=500, Q=900)</li>
 *   <li><b>Tables de pièces (PST)</b> — bonus/malus par case selon le type de pièce.
 *       Le roi interpole entre table MG et table EG selon la phase de jeu.</li>
 *   <li><b>Phase de jeu</b> — interpolation ouverture↔finale via le matériel restant.</li>
 * </ol>
 *
 * <h2>Extensions prévues</h2>
 * <ul>
 *   <li>Structure de pions (doublés, isolés, arriérés, passés)</li>
 *   <li>Mobilité (nombre de coups légaux)</li>
 *   <li>Sécurité du roi (pions bouclier)</li>
 * </ul>
 */
public final class PositionEvaluator {

    /** Score de mat (valeur absolue). Supérieur à tout score matériel possible. */
    public static final int MATE_SCORE = 1_000_000;

    /**
     * Matériel total de départ (sans les rois et sans les pions, qui ont peu d'influence
     * sur la phase). Sert à normaliser la phase de jeu entre 0.0 (finale) et 1.0 (ouverture).
     * = 2*(2*N + 2*B + 2*R + Q) = 2*(640 + 660 + 1000 + 900) = 6400
     */
    private static final int MAX_PHASE_MATERIAL =
            2 * (2 * Piece.KNIGHT.value + 2 * Piece.BISHOP.value
               + 2 * Piece.ROOK.value   + Piece.QUEEN.value);

    private PositionEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Évalue la position du point de vue des Blancs.
     *
     * @param state état bitboard courant
     * @return score en centipions (positif = avantage Blancs)
     */
    public static int evaluate(BitboardState state) {
        double phase = gamePhase(state);
        int score = 0;
        score += evaluateMaterialAndPst(state, Color.WHITE, phase);
        score -= evaluateMaterialAndPst(state, Color.BLACK, phase);
        return score;
    }

    /**
     * Retourne le score relatif au camp donné (utile dans MinMax/Negamax).
     * @param state état courant
     * @param side  camp qui évalue
     * @return score positif si {@code side} est avantagé
     */
    public static int evaluateFor(BitboardState state, Color side) {
        int raw = evaluate(state);
        return side == Color.WHITE ? raw : -raw;
    }

    /**
     * Évaluation purement matérielle (bonne base de départ).
     * Conservée pour les tests unitaires et la compatibilité.
     */
    public static int evaluateMaterial(BitboardState state) {
        int score = 0;
        for (Piece p : Piece.values()) {
            long white = state.getBitboard(Color.WHITE, p);
            long black = state.getBitboard(Color.BLACK, p);
            score += Long.bitCount(white) * p.value;
            score -= Long.bitCount(black) * p.value;
        }
        return score;
    }

    // ── Phase de jeu ─────────────────────────────────────────────────────────

    /**
     * Calcule la phase de jeu : 1.0 = ouverture/milieu, 0.0 = finale pure.
     * Basé sur le matériel restant des deux camps (hors pions et rois).
     */
    public static double gamePhase(BitboardState state) {
        int material = 0;
        for (Color c : Color.values()) {
            material += Long.bitCount(state.getBitboard(c, Piece.KNIGHT)) * Piece.KNIGHT.value;
            material += Long.bitCount(state.getBitboard(c, Piece.BISHOP)) * Piece.BISHOP.value;
            material += Long.bitCount(state.getBitboard(c, Piece.ROOK))   * Piece.ROOK.value;
            material += Long.bitCount(state.getBitboard(c, Piece.QUEEN))  * Piece.QUEEN.value;
        }
        return Math.min(1.0, (double) material / MAX_PHASE_MATERIAL);
    }

    // ── Matériel + PST ────────────────────────────────────────────────────────

    /**
     * Évalue le matériel et les PST pour un camp donné.
     * @param state état courant
     * @param color camp à évaluer
     * @param phase phase de jeu (1.0=ouverture, 0.0=finale)
     * @return score positif = avantageux pour {@code color}
     */
    private static int evaluateMaterialAndPst(BitboardState state, Color color, double phase) {
        int score = 0;
        boolean isBlack = color == Color.BLACK;

        for (Piece piece : Piece.values()) {
            long bb = state.getBitboard(color, piece);
            int[] pstMg = pstMgFor(piece);

            while (bb != 0) {
                int sq = Long.numberOfTrailingZeros(bb);
                bb &= bb - 1; // pop LSB

                score += piece.value;

                // Index PST : miroir vertical pour les Noirs
                int pstIdx = isBlack ? PieceSquareTables.miroir(sq) : sq;

                if (piece == Piece.KING) {
                    // Interpolation entre table MG et EG selon la phase
                    int mgBonus = PieceSquareTables.KING_MG[pstIdx];
                    int egBonus = PieceSquareTables.KING_EG[pstIdx];
                    score += (int) (mgBonus * phase + egBonus * (1.0 - phase));
                } else {
                    score += pstMg[pstIdx];
                }
            }
        }
        return score;
    }

    /**
     * Retourne la table PST (milieu de jeu) pour une pièce donnée.
     * Le roi utilise KING_MG (la sélection MG/EG est gérée dans l'appelant).
     */
    private static int[] pstMgFor(Piece piece) {
        return switch (piece) {
            case PAWN   -> PieceSquareTables.PAWN_MG;
            case KNIGHT -> PieceSquareTables.KNIGHT_MG;
            case BISHOP -> PieceSquareTables.BISHOP_MG;
            case ROOK   -> PieceSquareTables.ROOK_MG;
            case QUEEN  -> PieceSquareTables.QUEEN_MG;
            case KING   -> PieceSquareTables.KING_MG;
        };
    }
}
