package api;

import core.BitboardState;
import game.FenParser;
import game.GameResult;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
import model.Square;
import player.Player;
import rules.MoveGenerator;

import java.util.List;

/**
 * Façade principale du moteur d'échecs ChessOptiIa.
 */
public final class ChessAPI {

    private GameState gameState;
    private Player whitePlayer;
    private Player blackPlayer;

    public ChessAPI() {
        this.gameState = new GameState();
    }

    public ChessAPI(String fen) {
        this.gameState = new GameState(FenParser.parse(fen));
    }

    // ── Gestion des joueurs ──────────────────────────────────────────────────

    public void setWhitePlayer(Player player) { this.whitePlayer = player; }
    public void setBlackPlayer(Player player) { this.blackPlayer = player; }

    public Player getCurrentPlayer() {
        return gameState.getSideToMove() == Color.WHITE ? whitePlayer : blackPlayer;
    }

    /** Alias francophone de {@link #getCurrentPlayer()}. */
    public Player getJoueurActuel() { return getCurrentPlayer(); }

    public Move jouerCoupJoueur() {
        Player player = getCurrentPlayer();
        if (player == null) throw new IllegalStateException("Aucun joueur configuré pour ce camp.");
        Move move = player.getNextMove(gameState);
        jouerCoup(move);
        return move;
    }

    // ── Jouer un coup ────────────────────────────────────────────────────────

    /**
     * Joue un coup depuis deux cases (départ → arrivée).
     * Cherche parmi les coups légaux celui qui correspond à from/to,
     * quel que soit son type (normal, roque, en passant, promotion→dame par défaut).
     */
    public Move jouerCoup(Square dep, Square arr) {
        return jouerCoup(dep, arr, null);
    }

    /**
     * Joue un coup avec promotion explicite.
     * @param promotion pièce de promotion (null → dame par défaut si promotion)
     */
    public Move jouerCoup(Square dep, Square arr, Piece promotion) {
        List<Move> legal = gameState.getLegalMoves();
        Move found = null;
        for (Move m : legal) {
            if (m.from() != dep || m.to() != arr) continue;

            if (m.isPromotion()) {
                // Promotion : on cherche la pièce demandée, ou QUEEN par défaut
                Piece wanted = (promotion != null) ? promotion : Piece.QUEEN;
                if (m.promotionPiece() == wanted) { found = m; break; }
            } else if (m.isCastling()) {
                // Roque : on priorise ce coup devant un éventuel coup de roi normal
                found = m;
                break;
            } else {
                // Coup normal ou en passant : on prend le premier match from/to
                // mais on continue si un coup castling existe pour les mêmes cases
                if (found == null) found = m;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                "Coup illégal : " + dep.toAlgebraic() + " → " + arr.toAlgebraic());
        }
        gameState.applyMove(found);
        return found;
    }

    /**
     * Joue un coup en notation UCI (ex: "e2e4", "e7e8q").
     */
    public Move jouerCoup(String uci) {
        Move parsed = Move.fromUci(uci);
        return jouerCoup(parsed.from(), parsed.to(),
                         parsed.isPromotion() ? parsed.promotionPiece() : null);
    }

    /**
     * Joue un coup déjà construit (doit être légal).
     * Utilise une comparaison from/to/flags tolérante pour le roque.
     */
    public void jouerCoup(Move move) {
        // On cherche d'abord par equals() (encodage exact)
        List<Move> legal = gameState.getLegalMoves();
        if (legal.contains(move)) {
            gameState.applyMove(move);
            return;
        }
        // Fallback : cherche par from/to (utile si le flag de roque diffère)
        for (Move m : legal) {
            if (m.from() == move.from() && m.to() == move.to() && !m.isPromotion()) {
                gameState.applyMove(m);
                return;
            }
        }
        throw new IllegalArgumentException("Coup illégal : " + move.toUci());
    }

    // ── Consultation de l'état ───────────────────────────────────────────────

    public List<Move> getCoupsLegaux() { return gameState.getLegalMoves(); }

    public List<Move> getCoups(Square dep) {
        return MoveGenerator.generateLegalMovesFrom(gameState.getBitboardState(), dep);
    }

    public GameResult getEtatPartie()  { return gameState.getResult(); }
    public boolean estTerminee()       { return gameState.getResult().isOver(); }
    public boolean estEnEchec()        { return gameState.isCheck(); }
    public Color getCampActif()        { return gameState.getSideToMove(); }
    public BitboardState getBitboardState() { return gameState.getBitboardState(); }
    public GameState getGameState()    { return gameState; }

    // ── FEN ──────────────────────────────────────────────────────────────────

    public String toFEN() { return FenParser.toFen(gameState.getBitboardState()); }

    public void fromFEN(String fen) {
        gameState = new GameState(FenParser.parse(fen));
    }

    // ── Historique ───────────────────────────────────────────────────────────

    public boolean undo()  { return gameState.undo(); }
    public void reset()    { gameState.reset(); }

    // ── Affichage ────────────────────────────────────────────────────────────

    public String afficherEchiquier() {
        BitboardState state = gameState.getBitboardState();
        StringBuilder sb = new StringBuilder();
        sb.append("  +---+---+---+---+---+---+---+---+\n");
        for (int rank = 7; rank >= 0; rank--) {
            sb.append(rank + 1).append(" |");
            for (int file = 0; file < 8; file++) {
                Square sq = Square.fromIndex(file + rank * 8);
                BitboardState.PieceOnSquare pos = state.getPieceAt(sq);
                String symbol = pos == null ? " " : pos.piece().fenChar(pos.color());
                sb.append(" ").append(symbol).append(" |");
            }
            sb.append("\n  +---+---+---+---+---+---+---+---+\n");
        }
        sb.append("    a   b   c   d   e   f   g   h\n");
        sb.append("Tour : ").append(gameState.getSideToMove()).append("\n");
        sb.append("FEN  : ").append(toFEN()).append("\n");
        return sb.toString();
    }
}
