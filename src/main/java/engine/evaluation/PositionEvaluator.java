package engine.evaluation;

import core.BitboardState;
import model.Color;
import model.Piece;

/**
 * Évaluateur statique de position — orchestrateur de tous les modules.
 *
 * <h2>Score retourné</h2>
 * Toujours du point de vue des Blancs (positif = avantage Blanc).
 * La convention negamax de {@code AlphaBetaSearch} appelle {@link #evaluateFor}
 * pour obtenir le score du camp qui joue.
 *
 * <h2>Composantes</h2>
 * <pre>
 *   evaluate() =
 *       matériel + PST (MG/EG interpolés)   ← PositionEvaluator
 *     + structure de pions                   ← PawnEvaluator
 *     + mobilité + bonus pièces              ← MobilityEvaluator
 *     + sécurité du roi                      ← KingSafety
 * </pre>
 *
 * <h2>Phase de jeu</h2>
 * Calculée une seule fois par appel à {@code evaluate()}, puis transmise
 * à tous les sous-modules pour l'interpolation MG↔EG.
 * {@code phase = 1.0} = ouverture/milieu, {@code phase = 0.0} = finale pure.
 *
 * <h2>Valeurs matérielles de base</h2>
 * Interpolées MG/EG comme les PST :
 * <pre>
 *   Pion   : MG= 82, EG= 94
 *   Cavalier: MG=337, EG=281
 *   Fou    : MG=365, EG=297
 *   Tour   : MG=477, EG=512
 *   Dame   : MG=1025, EG=936
 * </pre>
 * Ces valeurs PeSTO remplacent les valeurs fixes précédentes et participent
 * à l'interpolation de phase exactement comme les PST.
 */
public final class PositionEvaluator {

    /** Score de mat absolu — supérieur à tout score matériel. */
    public static final int MATE_SCORE = 1_000_000;

    // ── Valeurs matérielles MG / EG (PeSTO) ──────────────────────────────────

    private static final int[] MATERIAL_MG = {
        82,   // PAWN
        337,  // KNIGHT
        365,  // BISHOP
        477,  // ROOK
        1025, // QUEEN
        0     // KING (valeur symbolique, pas de matériel)
    };

    private static final int[] MATERIAL_EG = {
        94,   // PAWN
        281,  // KNIGHT
        297,  // BISHOP
        512,  // ROOK
        936,  // QUEEN
        0     // KING
    };

    /**
     * Matériel total de départ (pièces lourdes + légers, sans pions ni rois),
     * utilisé pour normaliser la phase entre 0.0 et 1.0.
     * = 2*(2*KNIGHT_MG + 2*BISHOP_MG + 2*ROOK_MG + QUEEN_MG)
     * = 2*(674 + 730 + 954 + 1025) = 2*3383 = 6766
     */
    private static final int MAX_PHASE_MATERIAL =
        2 * (2 * MATERIAL_MG[Piece.KNIGHT.index]
           + 2 * MATERIAL_MG[Piece.BISHOP.index]
           + 2 * MATERIAL_MG[Piece.ROOK.index]
           +     MATERIAL_MG[Piece.QUEEN.index]);

    private PositionEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Évalue la position du point de vue des Blancs.
     * Appelle tous les sous-modules dans l'ordre.
     *
     * @param state état bitboard courant
     * @return score en centipions (positif = avantage Blancs)
     */
    public static int evaluate(BitboardState state) {
        double phase = gamePhase(state);

        int score = 0;
        score += materialAndPst(state, phase);
        score += PawnEvaluator.evaluate(state, phase);
        score += MobilityEvaluator.evaluate(state, phase);
        score += KingSafety.evaluate(state, phase);
        return score;
    }

    /**
     * Retourne le score relatif au camp donné (convention negamax).
     *
     * @param state état courant
     * @param side  camp qui évalue
     * @return score positif si {@code side} est avantagé
     */
    public static int evaluateFor(BitboardState state, Color side) {
        int raw = evaluate(state);
        return side == Color.WHITE ? raw : -raw;
    }

    /**
     * Calcule la phase de jeu : 1.0 = ouverture, 0.0 = finale pure.
     * Basée sur le matériel MG des pièces lourdes et légères restantes (sans pions/rois).
     */
    public static double gamePhase(BitboardState state) {
        int material = 0;
        for (Color c : Color.values()) {
            material += Long.bitCount(state.getBitboard(c, Piece.KNIGHT)) * MATERIAL_MG[Piece.KNIGHT.index];
            material += Long.bitCount(state.getBitboard(c, Piece.BISHOP)) * MATERIAL_MG[Piece.BISHOP.index];
            material += Long.bitCount(state.getBitboard(c, Piece.ROOK))   * MATERIAL_MG[Piece.ROOK.index];
            material += Long.bitCount(state.getBitboard(c, Piece.QUEEN))  * MATERIAL_MG[Piece.QUEEN.index];
        }
        return Math.min(1.0, (double) material / MAX_PHASE_MATERIAL);
    }

    // ── Matériel + PST (toutes pièces, MG+EG) ────────────────────────────────

    /**
     * Score matériel + PST pour les deux camps, avec interpolation MG/EG
     * appliquée à <b>toutes</b> les pièces (y compris pions, cavaliers, fous, tours, dame).
     */
    private static int materialAndPst(BitboardState state, double phase) {
        return materialAndPstSide(state, Color.WHITE, phase)
             - materialAndPstSide(state, Color.BLACK, phase);
    }

    private static int materialAndPstSide(BitboardState state, Color color, double phase) {
        int score = 0;
        boolean isBlack = (color == Color.BLACK);

        for (Piece piece : Piece.values()) {
            long bb = state.getBitboard(color, piece);
            int matMg = MATERIAL_MG[piece.index];
            int matEg = MATERIAL_EG[piece.index];
            int[] pstMg = pstMgFor(piece);
            int[] pstEg = pstEgFor(piece);

            while (bb != 0) {
                int sq = Long.numberOfTrailingZeros(bb);
                bb &= bb - 1;

                // Index PST : miroir vertical pour les Noirs
                int pstIdx = isBlack ? PieceSquareTables.miroir(sq) : sq;

                // Interpolation MG/EG pour le matériel et les PST ensemble
                int mgTotal = matMg + pstMg[pstIdx];
                int egTotal = matEg + pstEg[pstIdx];
                score += (int) (mgTotal * phase + egTotal * (1.0 - phase));
            }
        }
        return score;
    }

    // ── Sélection des tables PST ──────────────────────────────────────────────

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

    private static int[] pstEgFor(Piece piece) {
        return switch (piece) {
            case PAWN   -> PieceSquareTables.PAWN_EG;
            case KNIGHT -> PieceSquareTables.KNIGHT_EG;
            case BISHOP -> PieceSquareTables.BISHOP_EG;
            case ROOK   -> PieceSquareTables.ROOK_EG;
            case QUEEN  -> PieceSquareTables.QUEEN_EG;
            case KING   -> PieceSquareTables.KING_EG;
        };
    }
}
