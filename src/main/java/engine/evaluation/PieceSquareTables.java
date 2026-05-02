package engine.evaluation;

/**
 * Tables de pièces-cases (Piece-Square Tables, PST).
 *
 * <p>Chaque table est un tableau {@code int[64]} indexé par {@code Square.index}
 * (A1=0 … H8=63, soit file + rank*8). Les valeurs sont en centipions et
 * s'ajoutent à la valeur matérielle de base de la pièce.
 *
 * <p><b>Convention :</b> tables définies du point de vue des Blancs.
 * Pour les Noirs, utiliser {@link #miroir(int)} : {@code miroir(sq) = (7-rank)*8+file}.
 *
 * <p><b>Phase de jeu :</b> le roi dispose de deux tables (MG/EG).
 * Toutes les autres pièces utilisent une table unique (valide en ouverture/milieu de jeu).
 * L'interpolation se fait dans {@link PositionEvaluator}.
 *
 * <p>Valeurs issues de Tomasz Michniewski (Simplified Evaluation Function) :
 * <a href="https://www.chessprogramming.org/Simplified_Evaluation_Function">CPW — SEF</a>
 */
public final class PieceSquareTables {

    private PieceSquareTables() {}

    /**
     * Retourne l'index miroir (pour appliquer une table Blanc côté Noir).
     * @param squareIndex index 0-63 (A1=0, H8=63)
     * @return index miroir vertical
     */
    public static int miroir(int squareIndex) {
        int file = squareIndex % 8;
        int rank = squareIndex / 8;
        return (7 - rank) * 8 + file;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Les tables sont stockées "rang 8 en premier" (visuellement lisible
    // comme un échiquier vu du côté Blanc). Pour convertir en index A1=0 :
    //   index = (7 - visualRank) * 8 + file
    // On utilise donc un helper statique buildTable() ci-dessous.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construit un int[64] depuis une représentation visuelle rang-8-en-premier.
     * @param visual 64 valeurs, rang 8 (index 0) → rang 1 (index 56)
     */
    private static int[] buildTable(int... visual) {
        int[] table = new int[64];
        for (int visualIdx = 0; visualIdx < 64; visualIdx++) {
            int rank = 7 - (visualIdx / 8);
            int file = visualIdx % 8;
            int sqIndex = rank * 8 + file;
            table[sqIndex] = visual[visualIdx];
        }
        return table;
    }

    // ── Pions (MG) — favorise avance et centre ───────────────────────────────
    public static final int[] PAWN_MG = buildTable(
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    );

    // ── Cavaliers (MG) — bonus centre, malus bords ───────────────────────────
    public static final int[] KNIGHT_MG = buildTable(
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    );

    // ── Fous (MG) — grandes diagonales, développement ────────────────────────
    public static final int[] BISHOP_MG = buildTable(
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    );

    // ── Tours (MG) — colonnes ouvertes, 7e rang ──────────────────────────────
    public static final int[] ROOK_MG = buildTable(
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0
    );

    // ── Dame (MG) — ne pas sortir trop tôt ───────────────────────────────────
    public static final int[] QUEEN_MG = buildTable(
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    );

    // ── Roi — milieu de jeu (rester à l'abri, roquer) ────────────────────────
    public static final int[] KING_MG = buildTable(
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20
    );

    // ── Roi — finale (aller au centre, aider les pions) ──────────────────────
    public static final int[] KING_EG = buildTable(
        -50,-40,-30,-20,-20,-30,-40,-50,
        -30,-20,-10,  0,  0,-10,-20,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-30,  0,  0,  0,  0,-30,-30,
        -50,-30,-30,-30,-30,-30,-30,-50
    );
}
