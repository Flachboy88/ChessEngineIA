package game;

import core.BitboardState;
import model.Color;
import model.Move;
import model.Piece;
import rules.MoveGenerator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gère l'état courant d'une partie d'échecs.
 * Contient l'historique des états pour permettre l'annulation (undo).
 * Implémente la règle des 3 répétitions (nulle par répétition triple).
 */
public final class GameState {

    private BitboardState current;
    private final Deque<BitboardState> history = new ArrayDeque<>();

    /**
     * Compteur de positions : clé = hash de position, valeur = nombre d'occurrences.
     * Utilisé pour détecter la répétition triple.
     */
    private final Map<Long, Integer> positionCount = new HashMap<>();

    public GameState() {
        this.current = BitboardState.initialPosition();
        enregistrerPosition(this.current);
    }

    public GameState(BitboardState initial) {
        this.current = initial;
        enregistrerPosition(this.current);
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
        enregistrerPosition(current);
    }

    /**
     * Annule le dernier coup joué.
     * @return true si l'annulation a réussi, false si l'historique est vide
     */
    public boolean undo() {
        if (history.isEmpty()) return false;
        // Décrémenter le compteur de la position courante avant de revenir
        supprimerPosition(current);
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
        positionCount.clear();
        current = BitboardState.initialPosition();
        enregistrerPosition(current);
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
        if (isThreefoldRepetition()) return GameResult.DRAW_REPETITION;

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

    /** Vérifie si la position actuelle a été atteinte 3 fois (répétition triple). */
    private boolean isThreefoldRepetition() {
        long hash = positionHash(current);
        return positionCount.getOrDefault(hash, 0) >= 3;
    }

    /** Retourne vrai si le roi du camp courant est en échec. */
    public boolean isCheck() {
        return MoveGenerator.isInCheck(current, current.getSideToMove());
    }

    /**
     * Retourne le nombre total de pièces sur l'échiquier.
     * Utile pour la détection de la fin de partie et l'ajustement de profondeur.
     */
    public int getTotalPieceCount() {
        return Long.bitCount(current.getAllOccupancy());
    }

    /**
     * Retourne le nombre de pièces d'un camp donné.
     */
    public int getPieceCount(Color color) {
        return Long.bitCount(current.getOccupancy(color));
    }

    // ── Gestion des positions pour la répétition triple ───────────────────────

    /**
     * Calcule un hash de la position courante pour la détection de répétitions.
     * On inclut : les bitboards, le camp à jouer, les droits de roque, la cible en passant.
     */
    private static long positionHash(BitboardState state) {
        long hash = 0L;

        // Bitboards de toutes les pièces
        for (Color c : Color.values()) {
            for (Piece p : Piece.values()) {
                long bb = state.getBitboard(c, p);
                hash ^= bb * (long)(c.ordinal() * 7 + p.index + 1) * 0x9e3779b97f4a7c15L;
                hash = Long.rotateLeft(hash, 17);
            }
        }

        // Camp à jouer
        hash ^= state.getSideToMove().ordinal() * 0xbf58476d1ce4e5b9L;

        // Droits de roque
        hash ^= (long) state.getCastlingRights().getRights() * 0x94d049bb133111ebL;

        // En passant
        if (state.getEnPassantTarget() != null) {
            hash ^= (state.getEnPassantTarget().index + 1L) * 0x6c62272e07bb0142L;
        }

        return hash;
    }

    private void enregistrerPosition(BitboardState state) {
        long hash = positionHash(state);
        positionCount.merge(hash, 1, Integer::sum);
    }

    private void supprimerPosition(BitboardState state) {
        long hash = positionHash(state);
        positionCount.compute(hash, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    }
}
