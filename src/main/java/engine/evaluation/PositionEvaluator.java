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

        // ── Évaluation de finale spécialisée (KXvK) ──────────────────────────
        // En finale avec forte asymétrie matérielle (dame/tour/2fous vs rien),
        // l'évaluateur standard (PST+mobilité) ne suffit pas : il donne le même
        // score matériel dans toutes les positions et l'IA oscille.
        // L'évaluateur de finale encode la connaissance échiquéenne :
        //   - Pousser le roi adverse vers un bord/coin
        //   - Rapprocher le roi fort
        // Ce bonus s'ajoute au score matériel pour créer un gradient continu.
        int endgameBonus = evaluateEndgame(state);
        if (endgameBonus != 0) {
            // On retourne uniquement le score endgame dans les finales pures :
            // matériel + endgame guide l'IA de façon monotone vers le mat.
            return materialAndPst(state, phase256) + endgameBonus;
        }

        int score = 0;
        score += materialAndPst(state, phase256);
        score += evaluatePawnsCached(state, phase256);
        score += MobilityEvaluator.evaluate(state, phase256);
        score += KingSafety.evaluate(state, phase256);
        return score;
    }

    // ── Tables de distance bord/coin pour les finales ─────────────────────────

    /**
     * Distance d'une case au bord (0 = bord, 3 = centre).
     * Utilisée pour pousser le roi adverse vers le bord.
     */
    private static final int[] EDGE_DISTANCE = new int[64];

    /**
     * Distance d'une case au coin le plus proche (0 = coin, 6 = centre max).
     * Utilisée pour KBBvK (mat forcé uniquement dans un coin).
     */
    private static final int[] CORNER_DISTANCE = new int[64];

    static {
        for (int sq = 0; sq < 64; sq++) {
            int f = sq & 7;          // colonne 0-7
            int r = sq >> 3;         // rang 0-7
            int distEdge = Math.min(Math.min(f, 7 - f), Math.min(r, 7 - r));
            EDGE_DISTANCE[sq] = distEdge; // 0 = bord, 3 = centre
            int cornerDist = Math.min(
                Math.max(f, r),              // coin A8 (0,7) ou A1 (0,0)
                Math.min(Math.max(7-f, r),   // coin H8 (7,7)
                         Math.min(Math.max(f, 7-r),   // coin A1
                                  Math.max(7-f, 7-r))) // coin H1
            );
            CORNER_DISTANCE[sq] = cornerDist;
        }
    }

    /**
     * Distance de Chebyshev entre deux cases (nombre de coups de roi minimum).
     */
    private static int kingDistance(int sq1, int sq2) {
        int df = Math.abs((sq1 & 7) - (sq2 & 7));
        int dr = Math.abs((sq1 >> 3) - (sq2 >> 3));
        return Math.max(df, dr);
    }

    /**
     * Évaluateur spécialisé pour les finales à fort avantage matériel.
     * Retourne 0 si non applicable (laisse l'évaluateur standard travailler).
     *
     * <p>Couvre : KQvK, KRvK, KBBvK — les cas gérés par les built-ins.
     * Le score retourné est du point de vue des Blancs.
     *
     * <p>Formule : MATERIEL_BASE + bonus_bord(roi_faible) + bonus_proximite_rois
     * Le bonus est conçu pour être strictement monotone : plus le roi faible
     * est au bord et les rois sont proches, plus le score est élevé.
     * Cela crée un gradient continu que l'alpha-bêta peut exploiter.
     */
    private static int evaluateEndgame(BitboardState state) {
        int wQ = Long.bitCount(state.getBitboard(Color.WHITE, Piece.QUEEN));
        int wR = Long.bitCount(state.getBitboard(Color.WHITE, Piece.ROOK));
        int wB = Long.bitCount(state.getBitboard(Color.WHITE, Piece.BISHOP));
        int wN = Long.bitCount(state.getBitboard(Color.WHITE, Piece.KNIGHT));
        int wP = Long.bitCount(state.getBitboard(Color.WHITE, Piece.PAWN));
        int bQ = Long.bitCount(state.getBitboard(Color.BLACK, Piece.QUEEN));
        int bR = Long.bitCount(state.getBitboard(Color.BLACK, Piece.ROOK));
        int bB = Long.bitCount(state.getBitboard(Color.BLACK, Piece.BISHOP));
        int bN = Long.bitCount(state.getBitboard(Color.BLACK, Piece.KNIGHT));
        int bP = Long.bitCount(state.getBitboard(Color.BLACK, Piece.PAWN));

        int wKing = Long.numberOfTrailingZeros(state.getBitboard(Color.WHITE, Piece.KING));
        int bKing = Long.numberOfTrailingZeros(state.getBitboard(Color.BLACK, Piece.KING));
        if (wKing > 63 || bKing > 63) return 0;
        int kingDist = kingDistance(wKing, bKing);

        // ── KQvK : Blancs gagnent ─────────────────────────────────────────────
        if (wQ == 1 && wR == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            // Roi noir doit être au bord (EDGE_DISTANCE=0), rois proches
            int bEdge = EDGE_DISTANCE[bKing];   // 0=bord, 3=centre — on veut 0
            int bonus = 900                      // valeur dame
                + (3 - bEdge) * 20              // +60 si au bord, 0 si au centre
                + (14 - kingDist) * 4;          // rois proches = meilleur
            return bonus;
        }
        // ── KQvK : Noirs gagnent (symétrique) ────────────────────────────────
        if (bQ == 1 && bR == 0 && bB == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            int wEdge = EDGE_DISTANCE[wKing];
            int bonus = 900 + (3 - wEdge) * 20 + (14 - kingDist) * 4;
            return -bonus;
        }

        // ── KRvK : Blancs gagnent ─────────────────────────────────────────────
        if (wR == 1 && wQ == 0 && wB == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            int bEdge = EDGE_DISTANCE[bKing];
            int bonus = 500
                + (3 - bEdge) * 20
                + (14 - kingDist) * 4;
            return bonus;
        }
        if (bR == 1 && bQ == 0 && bB == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            int wEdge = EDGE_DISTANCE[wKing];
            int bonus = 500 + (3 - wEdge) * 20 + (14 - kingDist) * 4;
            return -bonus;
        }

        // ── KBBvK : Blancs gagnent (mat dans un coin de la couleur d'un fou) ──
        // Ici on pousse le roi adverse vers un coin quelconque d'abord.
        if (wB == 2 && wQ == 0 && wR == 0 && wN == 0 && wP == 0
                    && bQ == 0 && bR == 0 && bB == 0 && bN == 0 && bP == 0) {
            int bCorner = CORNER_DISTANCE[bKing]; // 0=coin, max=6
            int bonus = 600                        // 2 fous valent ~730 mais mat possible
                + (6 - bCorner) * 20              // pousser vers un coin
                + (14 - kingDist) * 4;
            return bonus;
        }
        if (bB == 2 && bQ == 0 && bR == 0 && bN == 0 && bP == 0
                    && wQ == 0 && wR == 0 && wB == 0 && wN == 0 && wP == 0) {
            int wCorner = CORNER_DISTANCE[wKing];
            int bonus = 600 + (6 - wCorner) * 20 + (14 - kingDist) * 4;
            return -bonus;
        }

        return 0; // pas une finale spécialisée
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
