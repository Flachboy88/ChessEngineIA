package game;

import core.BitboardState;
import model.Color;
import model.Move;
import model.Piece;
import rules.MoveGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Gère l'état courant d'une partie d'échecs.
 * Contient l'historique des états pour permettre l'annulation (undo).
 */
public final class GameState {

    private BitboardState current;
    private final Deque<BitboardState> history = new ArrayDeque<>();

    public GameState() {
        this.current = BitboardState.initialPosition();
    }

    public GameState(BitboardState initial) {
        this.current = initial;
    }

    /** Retourne l'état bitboard courant. */
    public BitboardState getBitboardState() {
        return current;
    }

    /** Retourne le camp qui doit jouer. */
    public Color getSideToMove() {
        return current.getSideToMove();
    }

    /** Retourne la liste des coups légaux depuis l'état courant. */
    public List<Move> getLegalMoves() {
        return MoveGenerator.generateLegalMoves(current);
    }

    /**
     * Applique un coup et pousse l'état précédent dans l'historique.
     * @param move coup légal à appliquer
     */
    public void applyMove(Move move) {
        history.push(current);
        current = MoveGenerator.applyMove(current, move);
    }

    /**
     * Annule le dernier coup joué.
     * @return true si l'annulation a réussi, false si l'historique est vide
     */
    public boolean undo() {
        if (history.isEmpty()) return false;
        current = history.pop();
        return true;
    }

    /** Retourne le nombre de coups dans l'historique. */
    public int getHistorySize() {
        return history.size();
    }

    /** Réinitialise la partie à la position de départ. */
    public void reset() {
        history.clear();
        current = BitboardState.initialPosition();
    }

    /** Retourne le résultat actuel de la partie. */
    public GameResult getResult() {
        List<Move> legalMoves = getLegalMoves();
        Color side = current.getSideToMove();
        boolean inCheck = MoveGenerator.isInCheck(current, side);

        if (legalMoves.isEmpty()) {
            if (inCheck) {
                return side == Color.WHITE ? GameResult.BLACK_WINS : GameResult.WHITE_WINS;
            } else {
                return GameResult.STALEMATE;
            }
        }

        if (current.getHalfMoveClock() >= 100) return GameResult.DRAW_50_MOVES;
        if (isInsufficientMaterial()) return GameResult.DRAW_INSUFFICIENT_MATERIAL;

        return GameResult.IN_PROGRESS;
    }

    /** Vérifie le matériel insuffisant (K vs K, K+N vs K, K+B vs K). */
    private boolean isInsufficientMaterial() {
        for (Color c : Color.values()) {
            if (current.getBitboard(c, Piece.PAWN) != 0) return false;
            if (current.getBitboard(c, Piece.ROOK) != 0) return false;
            if (current.getBitboard(c, Piece.QUEEN) != 0) return false;
        }
        // Seuls rois restants (+ éventuellement un cavalier ou un fou)
        return true;
    }

    /** Retourne vrai si le roi du camp courant est en échec. */
    public boolean isCheck() {
        return MoveGenerator.isInCheck(current, current.getSideToMove());
    }
}
