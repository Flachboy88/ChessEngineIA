package game;

import core.BitboardState;
import model.Color;
import model.Piece;
import model.Square;
import model.CastlingRights;

/**
 * Conversion entre état bitboard et notation FEN (Forsyth-Edwards Notation).
 *
 * Format FEN : "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
 *   1. Position des pièces (rangée 8 → rangée 1, colonnes a→h)
 *   2. Tour au jeu (w/b)
 *   3. Droits de roque (KQkq ou -)
 *   4. Cible en passant (ex: e6 ou -)
 *   5. Compteur demi-coups
 *   6. Numéro de coup complet
 */
public final class FenParser {

    public static final String STARTING_FEN =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private FenParser() {}

    /**
     * Parse une chaîne FEN et retourne l'état correspondant.
     */
    public static BitboardState parse(String fen) {
        String[] parts = fen.trim().split("\\s+");
        if (parts.length < 4) throw new IllegalArgumentException("FEN invalide : " + fen);

        BitboardState state = BitboardState.empty();

        // 1. Position des pièces
        String[] ranks = parts[0].split("/");
        if (ranks.length != 8) throw new IllegalArgumentException("FEN : mauvais nombre de rangées");

        for (int rankIdx = 0; rankIdx < 8; rankIdx++) {
            int rank = 7 - rankIdx; // FEN commence par la rangée 8
            String rankStr = ranks[rankIdx];
            int file = 0;
            for (char c : rankStr.toCharArray()) {
                if (Character.isDigit(c)) {
                    file += c - '0';
                } else {
                    Color color = Character.isUpperCase(c) ? Color.WHITE : Color.BLACK;
                    Piece piece = Piece.fromFenChar(c);
                    Square sq = Square.fromIndex(file + rank * 8);
                    state = state.withPiece(color, piece, sq);
                    file++;
                }
            }
        }

        // 2. Tour au jeu
        Color side = parts[1].equals("w") ? Color.WHITE : Color.BLACK;
        state = state.withSideToMove(side);

        // 3. Droits de roque
        state = state.withCastlingRights(CastlingRights.fromFen(parts[2]));

        // 4. En passant
        Square ep = parts[3].equals("-") ? null : Square.fromAlgebraic(parts[3]);
        state = state.withEnPassantTarget(ep);

        // 5. Demi-coups
        int halfMove = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        state = state.withHalfMoveClock(halfMove);

        // 6. Numéro de coup
        int fullMove = parts.length > 5 ? Integer.parseInt(parts[5]) : 1;
        state = state.withFullMoveNumber(fullMove);

        return state;
    }

    /**
     * Convertit un état bitboard en chaîne FEN.
     */
    public static String toFen(BitboardState state) {
        StringBuilder sb = new StringBuilder();

        // 1. Position des pièces
        for (int rankIdx = 7; rankIdx >= 0; rankIdx--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Square sq = Square.fromIndex(file + rankIdx * 8);
                BitboardState.PieceOnSquare pos = state.getPieceAt(sq);
                if (pos == null) {
                    empty++;
                } else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(pos.piece().fenChar(pos.color()));
                }
            }
            if (empty > 0) sb.append(empty);
            if (rankIdx > 0) sb.append('/');
        }

        sb.append(' ');
        sb.append(state.getSideToMove() == Color.WHITE ? 'w' : 'b');
        sb.append(' ');
        sb.append(state.getCastlingRights().toFen());
        sb.append(' ');
        sb.append(state.getEnPassantTarget() == null ? "-" : state.getEnPassantTarget().toAlgebraic());
        sb.append(' ');
        sb.append(state.getHalfMoveClock());
        sb.append(' ');
        sb.append(state.getFullMoveNumber());

        return sb.toString();
    }
}
