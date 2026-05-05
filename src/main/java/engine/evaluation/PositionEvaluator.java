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
 *     + structure de pions (avec cache)      ← PawnEvaluator + Pawn Hash Table
 *     + mobilité + bonus pièces              ← MobilityEvaluator
 *     + sécurité du roi                      ← KingSafety
 * </pre>
 *
 * <h2>Phase de jeu</h2>
 * Calculée une seule fois par appel à {@code evaluate()}, puis transmise
 * à tous les sous-modules pour l'interpolation MG↔EG.
 *
 * <h2>Pawn Hash Table</h2>
 * La structure de pions change dans ~10% des coups seulement. Un cache de
 * 65536 entrées (≈ 512 Ko) évite 90% des recalculs de {@link PawnEvaluator}.
 * La clé est un hash Fibonacci des bitboards de pions blancs et noirs.
 * Le score est calculé à phase fixe (la phase varie peu pour une même structure).
 *
 * <h2>Valeurs matérielles de base (PeSTO)</h2>
 * <pre>
 *   Pion   : MG= 82, EG= 94
 *   Cavalier: MG=337, EG=281
 *   Fou    : MG=365, EG=297
 *   Tour   : MG=477, EG=512
 *   Dame   : MG=1025, EG=936
 * </pre>
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
        0     // KING
    };

    private static final int[] MATERIAL_EG = {
        94,   // PAWN
        281,  // KNIGHT
        297,  // BISHOP
        512,  // ROOK
        936,  // QUEEN
        0     // KING
    };

    private static final int MAX_PHASE_MATERIAL =
        2 * (2 * MATERIAL_MG[Piece.KNIGHT.index]
           + 2 * MATERIAL_MG[Piece.BISHOP.index]
           + 2 * MATERIAL_MG[Piece.ROOK.index]
           +     MATERIAL_MG[Piece.QUEEN.index]);

    // ── Pawn Hash Table ───────────────────────────────────────────────────────

    private static final int    PAWN_TT_SIZE   = 1 << 16; // 65536 entrées ≈ 512 Ko
    private static final int    PAWN_TT_MASK   = PAWN_TT_SIZE - 1;
    private static final long[] PAWN_TT_HASH   = new long[PAWN_TT_SIZE];
    private static final int[]  PAWN_TT_SCORE  = new int[PAWN_TT_SIZE];
    /** Sentinelle : valeur impossible pour détecter une entrée vide. */
    private static final int    PAWN_TT_EMPTY  = Integer.MIN_VALUE;

    static {
        java.util.Arrays.fill(PAWN_TT_SCORE, PAWN_TT_EMPTY);
    }

    private PositionEvaluator() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Évalue la position du point de vue des Blancs.
     * Utilise le cache pions pour éviter les recalculs.
     *
     * @param state état bitboard courant
     * @return score en centipions (positif = avantage Blancs)
     */
    public static int evaluate(BitboardState state) {
        int phase256 = gamePhase(state);

        int score = 0;
        score += materialAndPst(state, phase256);
        score += evaluatePawnsCached(state, phase256);
        score += MobilityEvaluator.evaluate(state, phase256);
        score += KingSafety.evaluate(state, phase256);
        return score;
    }

    /**
     * Retourne le score relatif au camp donné (convention negamax).
     */
    public static int evaluateFor(BitboardState state, Color side) {
        int raw = evaluate(state);
        return side == Color.WHITE ? raw : -raw;
    }

    /**
     * Calcule la phase de jeu : 256 = ouverture pure, 0 = finale pure.
     */
    public static int gamePhase(BitboardState state) {
        int material = 0;
        for (Color c : Color.values()) {
            material += Long.bitCount(state.getBitboard(c, Piece.KNIGHT)) * MATERIAL_MG[Piece.KNIGHT.index];
            material += Long.bitCount(state.getBitboard(c, Piece.BISHOP)) * MATERIAL_MG[Piece.BISHOP.index];
            material += Long.bitCount(state.getBitboard(c, Piece.ROOK))   * MATERIAL_MG[Piece.ROOK.index];
            material += Long.bitCount(state.getBitboard(c, Piece.QUEEN))  * MATERIAL_MG[Piece.QUEEN.index];
        }
        return Math.min(256, material * 256 / MAX_PHASE_MATERIAL);
    }

    /**
     * Vide le cache pions. À appeler entre deux parties ou lors des tests.
     */
    public static void clearPawnCache() {
        java.util.Arrays.fill(PAWN_TT_SCORE, PAWN_TT_EMPTY);
    }

    // ── Pawn Hash Table ───────────────────────────────────────────────────────

    /**
     * Évalue la structure de pions avec cache.
     *
     * <p>La clé est calculée à partir des bitboards de pions des deux camps via
     * un hash de Fibonacci (multiplication par une constante 64-bit irrationnelle).
     * En cas de collision, on recalcule silencieusement (no-harm policy).
     *
     * <p>Note : on passe {@code phase256} pour être exact, mais la structure de
     * pions varie rarement avec la phase → le cache reste très efficace.
     */
    private static int evaluatePawnsCached(BitboardState state, int phase256) {
        long whitePawns = state.getBitboard(Color.WHITE, Piece.PAWN);
        long blackPawns = state.getBitboard(Color.BLACK, Piece.PAWN);

        // Hash de Fibonacci des deux bitboards de pions
        long pawnHash = whitePawns * 0x9E3779B97F4A7C15L
                      ^ blackPawns * 0x6C62272E07BB0142L;
        int idx = (int)(pawnHash & PAWN_TT_MASK);

        if (PAWN_TT_HASH[idx] == pawnHash && PAWN_TT_SCORE[idx] != PAWN_TT_EMPTY) {
            return PAWN_TT_SCORE[idx];
        }

        int score = PawnEvaluator.evaluate(state, phase256);
        PAWN_TT_HASH[idx]  = pawnHash;
        PAWN_TT_SCORE[idx] = score;
        return score;
    }

    // ── Matériel + PST (toutes pièces, MG+EG) ────────────────────────────────

    private static int materialAndPst(BitboardState state, int phase256) {
        return materialAndPstSide(state, Color.WHITE, phase256)
             - materialAndPstSide(state, Color.BLACK, phase256);
    }

    private static int materialAndPstSide(BitboardState state, Color color, int phase256) {
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

                int pstIdx = isBlack ? PieceSquareTables.miroir(sq) : sq;

                int mgTotal = matMg + pstMg[pstIdx];
                int egTotal = matEg + pstEg[pstIdx];
                score += PawnEvaluator.interpolate(mgTotal, egTotal, phase256);
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
