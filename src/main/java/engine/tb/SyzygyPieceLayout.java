package engine.tb;

import core.BitboardState;
import model.Color;
import model.Piece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calcule le nom canonique d'un fichier Syzygy et l'index de position.
 *
 * <h2>Encodage sans pions (tbprobe.c : encode_piece)</h2>
 * <pre>
 *   idx  = KING_MAP[wKing]                    (0-9,  facteur 10)
 *        + bKing_adj * 10                     (0-62, facteur 63)
 *        + piece0_adj * 630                   (0-61, facteur 62)
 *        + piece1_adj * 630*62  ...
 * </pre>
 * Le roi fort est dans le triangle a1-d1-d4 (10 cases) via les symétries.
 * Le roi faible est encodé sur les 63 cases restantes (hors roi fort).
 * Chaque pièce suivante est encodée sur N cases libres restantes.
 *
 * <h2>Encodage avec pions (tbprobe.c : encode_pawn)</h2>
 * <pre>
 *   idx  = PAWN_IDX[pawn0]                    (0-47, facteur 48)
 *        + wKing * 48                         (0-63, facteur 64)
 *        + bKing * 48*64                      (0-63, facteur 64)
 *        + autres_pièces ...
 * </pre>
 * Les pions du côté fort sont encodés en premier (rangées 2-7, 48 cases).
 * Ensuite les rois (64 cases chacun), puis les autres pièces.
 */
public final class SyzygyPieceLayout {

    private static final int[] PIECE_ORDER = { 4, 3, 2, 1, 0 }; // Q, R, B, N, P

    static final int[]   KING_MAP     = new int[64];
    static final int[]   KING_MAP_INV = new int[10];
    static final long[][] BINOMIAL    = new long[65][7];
    static final int[]   PAWN_IDX     = new int[64];
    static final int[]   PAWN_IDX_INV = new int[48];

    static {
        buildKingMap();
        buildBinomial();
        buildPawnIdx();
    }

    private SyzygyPieceLayout() {}

    // =========================================================================
    // API publique
    // =========================================================================

    public static String getTableName(BitboardState state) {
        String white = encodeSide(state, Color.WHITE);
        String black = encodeSide(state, Color.BLACK);
        if (compareSides(white, black) < 0) {
            return "K" + stripK(black) + "v" + "K" + stripK(white);
        }
        return "K" + stripK(white) + "v" + "K" + stripK(black);
    }

    public static boolean whiteIsStrongSide(BitboardState state) {
        String white = encodeSide(state, Color.WHITE);
        String black = encodeSide(state, Color.BLACK);
        return compareSides(white, black) >= 0;
    }

    public static long computeIndex(BitboardState state, boolean strongWhite) {
        Color strong = strongWhite ? Color.WHITE : Color.BLACK;
        Color weak   = strong.opposite();

        int sKing = Long.numberOfTrailingZeros(state.getBitboard(strong, Piece.KING));
        int wKing = Long.numberOfTrailingZeros(state.getBitboard(weak,   Piece.KING));

        List<Integer> strongPieces = collectNonKing(state, strong);
        List<Integer> weakPieces   = collectNonKing(state, weak);

        if (hasPawns(state)) {
            return encodeWithPawns(sKing, wKing, strongPieces, weakPieces, state, strong);
        } else {
            return encodeNoPawns(sKing, wKing, strongPieces, weakPieces);
        }
    }

    // =========================================================================
    // Encodage sans pions  (tbprobe.c : encode_piece)
    // =========================================================================

    private static long encodeNoPawns(int sKing, int wKing,
                                       List<Integer> strongPieces,
                                       List<Integer> weakPieces) {
        // ── 1. Symétrie : amener le roi fort dans le triangle a1-d1-d4 ─────────
        int mirror = getMirrorMask(sKing);
        sKing        = applyMirror(sKing, mirror);
        wKing        = applyMirror(wKing, mirror);
        strongPieces = applyMirrorList(strongPieces, mirror);
        weakPieces   = applyMirrorList(weakPieces,   mirror);

        int kingIdx = KING_MAP[sKing];
        if (kingIdx < 0) return -1;

        // ── 2. Roi fort (facteur 10) ──────────────────────────────────────────
        long idx    = kingIdx;
        long factor = 10L;

        // ── 3. Roi faible : 63 cases (toutes sauf celle du roi fort) ─────────
        //  adjustIdx saute simplement la case sKing
        int wkAdj = wKing - (wKing > sKing ? 1 : 0);
        idx    += factor * wkAdj;
        factor *= 63L;

        // ── 4. Pièces fortes puis faibles ─────────────────────────────────────
        //  On garde une liste des cases occupées pour sauter les cases déjà prises
        List<Integer> occupied = new ArrayList<>();
        occupied.add(sKing);
        occupied.add(wKing);

        for (int sq : strongPieces) {
            int adj = adjustOccupied(sq, occupied);
            idx    += factor * adj;
            factor *= (64 - occupied.size());
            occupied.add(sq);
        }
        for (int sq : weakPieces) {
            int adj = adjustOccupied(sq, occupied);
            idx    += factor * adj;
            factor *= (64 - occupied.size());
            occupied.add(sq);
        }

        return idx;
    }

    // =========================================================================
    // Encodage avec pions  (tbprobe.c : encode_pawn)
    // =========================================================================

    private static long encodeWithPawns(int sKing, int wKing,
                                         List<Integer> strongPieces,
                                         List<Integer> weakPieces,
                                         BitboardState state, Color strong) {
        // ── 1. Symétrie horizontale : premier pion fort dans les colonnes a-d ─
        //  On trie les pions du côté fort par indice croissant, le premier
        //  pion détermine si on miroir horizontalement.
        List<Integer> sPawns = collectPiece(state, strong, Piece.PAWN);
        // Pion canonique = celui à la colonne la plus basse après tri
        int firstPawnFile = sPawns.isEmpty() ? 0 : (sPawns.get(0) & 7);
        boolean mirrorH = (firstPawnFile > 3);
        if (mirrorH) {
            sKing        = applyMirror(sKing, 1);
            wKing        = applyMirror(wKing, 1);
            strongPieces = applyMirrorList(strongPieces, 1);
            weakPieces   = applyMirrorList(weakPieces,   1);
            // Recalculer sPawns après miroir
            sPawns = new ArrayList<>();
            for (int sq : strongPieces) {
                if ((state.getBitboard(strong, Piece.PAWN) & (1L << applyMirror(sq, 1))) != 0
                        || isPawnSquare(state, strong, applyMirror(sq, 1))) {
                    sPawns.add(sq);
                }
            }
            // Plus simple : recollect depuis strongPieces mirrorés
            sPawns = new ArrayList<>();
            for (int sq : strongPieces) {
                // sq est déjà mirroré, on vérifie dans l'état original mirrored
                // On identifie les pions comme ceux issus de Piece.PAWN
                if (isPawnInList(state, strong, applyMirror(sq, 1))) {
                    sPawns.add(sq);
                }
            }
        }

        // ── 2. Pions du côté fort en premier (rangées 2-7, 48 cases) ──────────
        long idx    = 0;
        long factor = 1L;
        List<Integer> pawnSqs = new ArrayList<>();

        for (int sq : strongPieces) {
            if (sPawns.contains(sq)) {
                int pawnIdx = PAWN_IDX[sq];
                if (pawnIdx < 0) return -1; // pion sur rangée 1 ou 8 → invalide
                idx    += factor * pawnIdx;
                factor *= 48L;
                pawnSqs.add(sq);
            }
        }

        // ── 3. Rois (64 cases chacun) ──────────────────────────────────────────
        idx    += factor * sKing;
        factor *= 64L;
        idx    += factor * wKing;
        factor *= 64L;

        // ── 4. Pièces non-pion du côté fort ───────────────────────────────────
        List<Integer> occupied = new ArrayList<>(pawnSqs);
        occupied.add(sKing);
        occupied.add(wKing);

        for (int sq : strongPieces) {
            if (!pawnSqs.contains(sq)) {
                int adj = adjustOccupied(sq, occupied);
                idx    += factor * adj;
                factor *= (64 - occupied.size());
                occupied.add(sq);
            }
        }

        // ── 5. Pièces du côté faible ───────────────────────────────────────────
        for (int sq : weakPieces) {
            int adj = adjustOccupied(sq, occupied);
            idx    += factor * adj;
            factor *= (64 - occupied.size());
            occupied.add(sq);
        }

        return idx;
    }

    // =========================================================================
    // Utilitaires
    // =========================================================================

    /**
     * Détermine le masque de symétrie pour amener le roi fort
     * dans le triangle a1-d1-d4 (file ≤ rank, file ≤ 3).
     */
    static int getMirrorMask(int sq) {
        int file = sq & 7;
        int rank = sq >> 3;
        int mask = 0;
        if (file > 3) { file = 7 - file; mask |= 1; }  // miroir horizontal
        if (rank > 3) { rank = 7 - rank; mask |= 2; }  // miroir vertical
        if (file > rank)              mask |= 4;        // miroir diagonal
        return mask;
    }

    static int applyMirror(int sq, int mask) {
        if ((mask & 1) != 0) sq ^= 7;                          // horizontal
        if ((mask & 2) != 0) sq ^= 56;                         // vertical
        if ((mask & 4) != 0) sq = ((sq & 7) << 3) | (sq >> 3); // diagonal
        return sq;
    }

    private static List<Integer> applyMirrorList(List<Integer> list, int mask) {
        List<Integer> r = new ArrayList<>(list.size());
        for (int sq : list) r.add(applyMirror(sq, mask));
        r.sort(Comparator.naturalOrder());
        return r;
    }

    /**
     * Retourne l'index ajusté d'une case en sautant toutes les cases
     * déjà occupées qui sont ≤ sq.
     * Exemple : sq=10, occupied=[3,7] → idx = 10 - 2 = 8
     */
    private static int adjustOccupied(int sq, List<Integer> occupied) {
        int idx = sq;
        for (int occ : occupied) {
            if (occ <= sq) idx--;
        }
        return Math.max(0, idx);
    }

    // =========================================================================
    // Nommage du fichier
    // =========================================================================

    private static String encodeSide(BitboardState state, Color color) {
        StringBuilder sb = new StringBuilder("K");
        for (int pieceIdx : PIECE_ORDER) {
            Piece piece = Piece.fromIndex(pieceIdx);
            if (piece == Piece.KING) continue;
            int count = Long.bitCount(state.getBitboard(color, piece));
            sb.append(piece.symbol.repeat(count));
        }
        return sb.toString();
    }

    private static String stripK(String s) { return s.substring(1); }

    private static int compareSides(String w, String b) {
        int d = materialScore(w) - materialScore(b);
        return d != 0 ? d : w.compareTo(b);
    }

    private static int materialScore(String side) {
        int s = 0;
        for (char c : side.toCharArray()) s += switch (c) {
            case 'Q' -> 9; case 'R' -> 5; case 'B','N' -> 3; case 'P' -> 1; default -> 0;
        };
        return s;
    }

    // =========================================================================
    // Collecte des pièces
    // =========================================================================

    private static List<Integer> collectNonKing(BitboardState state, Color color) {
        List<Integer> r = new ArrayList<>();
        for (int pidx : PIECE_ORDER) {
            Piece p = Piece.fromIndex(pidx);
            if (p == Piece.KING) continue;
            long bb = state.getBitboard(color, p);
            while (bb != 0) { r.add(Long.numberOfTrailingZeros(bb)); bb &= bb - 1; }
        }
        r.sort(Comparator.naturalOrder());
        return r;
    }

    private static List<Integer> collectPiece(BitboardState state, Color color, Piece piece) {
        List<Integer> r = new ArrayList<>();
        long bb = state.getBitboard(color, piece);
        while (bb != 0) { r.add(Long.numberOfTrailingZeros(bb)); bb &= bb - 1; }
        r.sort(Comparator.naturalOrder());
        return r;
    }

    private static boolean hasPawns(BitboardState state) {
        return (state.getBitboard(Color.WHITE, Piece.PAWN)
              | state.getBitboard(Color.BLACK, Piece.PAWN)) != 0L;
    }

    private static boolean isPawnSquare(BitboardState state, Color color, int sq) {
        return (state.getBitboard(color, Piece.PAWN) & (1L << sq)) != 0;
    }

    /** Vérifie si une case (déjà mirrorée) correspond à un pion dans l'état original. */
    private static boolean isPawnInList(BitboardState state, Color color, int originalSq) {
        return isPawnSquare(state, color, originalSq);
    }

    // =========================================================================
    // Initialisation des tables statiques
    // =========================================================================

    private static void buildKingMap() {
        for (int sq = 0; sq < 64; sq++) KING_MAP[sq] = -1;
        int idx = 0;
        // Triangle : file <= rank ET file <= 3
        // Cases : a1,b2,c3,d4, a2,b3,c4, a3,b4, a4  → 10 cases
        // Ordre tbprobe : par rang croissant, par colonne croissante dans chaque rang
        for (int rank = 0; rank < 4; rank++) {
            for (int file = 0; file <= rank; file++) {
                int sq = rank * 8 + file;
                KING_MAP[sq]      = idx;
                KING_MAP_INV[idx] = sq;
                idx++;
            }
        }
    }

    private static void buildBinomial() {
        for (int n = 0; n <= 64; n++) {
            BINOMIAL[n][0] = 1;
            for (int k = 1; k <= 6 && k <= n; k++)
                BINOMIAL[n][k] = BINOMIAL[n-1][k-1] + BINOMIAL[n-1][k];
        }
    }

    private static void buildPawnIdx() {
        for (int sq = 0; sq < 64; sq++) PAWN_IDX[sq] = -1;
        int idx = 0;
        // Rangées 2-7 (rank 1-6 en 0-indexed), colonnes a-h
        // Ordre : a2,b2,...,h2, a3,...,h7  (48 cases)
        for (int rank = 1; rank < 7; rank++) {
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                PAWN_IDX[sq]       = idx;
                PAWN_IDX_INV[idx]  = sq;
                idx++;
            }
        }
    }
}
