package game;

import core.BitboardState;
import engine.search.AlphaBetaSearch;
import engine.tb.SyzygyTablebase;
import engine.tb.WDL;
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

        if (isInsufficientMaterial()) return GameResult.DRAW_INSUFFICIENT_MATERIAL;

        // Ne pas déclarer nulle (50 coups / répétition) si la tablebase sait que
        // la position est théoriquement gagnante — l'IA doit continuer à jouer.
        // IMPORTANT : on ne consulte les built-ins que si la TB est réellement disponible
        // (fichiers chargés). Les built-ins seuls sur une TB "disabled" bloqueraient
        // la règle des 50 coups sur toutes les positions ≤ N pièces.
        boolean tbWin = isTbWin();
        if (!tbWin && current.getHalfMoveClock() >= 100) return GameResult.DRAW_50_MOVES;

        // La répétition triple ne s'applique PAS si la TB confirme une victoire forcée.
        // Mais la répétition est vérifiée AVANT de déclarer nulle pour garder la cohérence
        // dans les positions théoriquement nulles (KRvKB, etc.).
        if (!tbWin && isThreefoldRepetition()) return GameResult.DRAW_REPETITION;

        return GameResult.IN_PROGRESS;
    }

    /**
     * Vérifie le matériel insuffisant selon les règles officielles FIDE :
     * - K vs K
     * - K+N vs K
     * - K+B vs K
     * - K+B vs K+B (fous de même couleur — FIDE) ou couleurs opposées (pratique)
     *
     * NB : KBB, KBN, KNN (≥2 pièces mineures d'un seul camp) peuvent forcer le mat → pas nulle.
     */
    private boolean isInsufficientMaterial() {
        int wQ = Long.bitCount(current.getBitboard(Color.WHITE, Piece.QUEEN));
        int wR = Long.bitCount(current.getBitboard(Color.WHITE, Piece.ROOK));
        int wB = Long.bitCount(current.getBitboard(Color.WHITE, Piece.BISHOP));
        int wN = Long.bitCount(current.getBitboard(Color.WHITE, Piece.KNIGHT));
        int wP = Long.bitCount(current.getBitboard(Color.WHITE, Piece.PAWN));
        int bQ = Long.bitCount(current.getBitboard(Color.BLACK, Piece.QUEEN));
        int bR = Long.bitCount(current.getBitboard(Color.BLACK, Piece.ROOK));
        int bB = Long.bitCount(current.getBitboard(Color.BLACK, Piece.BISHOP));
        int bN = Long.bitCount(current.getBitboard(Color.BLACK, Piece.KNIGHT));
        int bP = Long.bitCount(current.getBitboard(Color.BLACK, Piece.PAWN));

        // Si l'un des camps a une pièce majeure ou un pion → pas nulle
        if (wQ + wR + wP + bQ + bR + bP > 0) return false;

        // Matériel blanc et noir (hors roi)
        int wMinor = wB + wN;
        int bMinor = bB + bN;

        // K vs K
        if (wMinor == 0 && bMinor == 0) return true;

        // K+N vs K  ou  K+B vs K (une seule pièce mineure d'un côté, rien de l'autre)
        if (wMinor == 1 && bMinor == 0) return true;
        if (bMinor == 1 && wMinor == 0) return true;

        // K+B vs K+B — EXACTEMENT un fou de chaque côté (pas deux du même camp !)
        // Bug corrigé : la condition wB==1 && bB==1 garantit que c'est bien KBvKB
        // et non KBBvK (qui serait wB==2 && bB==0, déjà exclu par wMinor==2 ci-dessus).
        if (wB == 1 && wN == 0 && bB == 1 && bN == 0) {
            // Nulle dans tous les cas (même couleur ou couleurs opposées) :
            // avec jeu parfait c'est toujours nulle en pratique.
            return true;
        }

        // Tous les autres cas (KBB, KBN, KNN, ...) → pas nulle
        return false;
    }

    /** Vérifie si la position actuelle a été atteinte 3 fois (répétition triple). */
    private boolean isThreefoldRepetition() {
        long hash = positionHash(current);
        return positionCount.getOrDefault(hash, 0) >= 3;
    }

    /**
     * Retourne true si la tablebase (avec fichiers réels) considère la position comme
     * gagnante pour l'un ou l'autre des camps.
     *
     * IMPORTANT : on n'utilise PAS les built-ins ici, car ils sont actifs même sur une TB
     * "disabled" (builtInOnly=true) et bloqueraient la règle des 50 coups sur toutes les
     * finales simples. On ne bloque les règles de nullité que si de vrais fichiers .rtbw
     * sont disponibles et confirment une victoire forcée.
     */
    private boolean isTbWin() {
        SyzygyTablebase tb = AlphaBetaSearch.getTablebase();
        // On ne consulte que si la TB a de vrais fichiers (available=true)
        if (!tb.isAvailable()) return false;
        if (!tb.canProbe(current)) return false;
        WDL wdl = tb.probe(current);
        return wdl.isKnown() && !wdl.isDraw();
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
