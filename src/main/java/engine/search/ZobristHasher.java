package engine.search;

/**
 * Classe déplacée dans {@code core.ZobristHasher}.
 * Conservée ici uniquement pour éviter les erreurs de compilation
 * sur d'éventuels imports restants.
 *
 * @see core.ZobristHasher
 */
public final class ZobristHasher {

    public static final long[][][] PIECE_HASH      = core.ZobristHasher.PIECE_HASH;
    public static final long[]     EN_PASSANT_HASH = core.ZobristHasher.EN_PASSANT_HASH;
    public static final long[]     CASTLING_HASH   = core.ZobristHasher.CASTLING_HASH;
    public static final long       SIDE_TO_MOVE_HASH = core.ZobristHasher.SIDE_TO_MOVE_HASH;

    private ZobristHasher() {}

    public static long computeHash(long[][] bitboards, model.Color sideToMove,
                                   int castlingMask, int enPassantFile) {
        return core.ZobristHasher.computeHash(bitboards, sideToMove, castlingMask, enPassantFile);
    }
}
