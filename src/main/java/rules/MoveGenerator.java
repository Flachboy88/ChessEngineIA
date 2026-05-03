package rules;

import core.Bitboard;
import core.BitboardState;
import model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Génère tous les coups légaux depuis un état donné.
 * Utilise des bitboards pour la génération pseudo-légale,
 * puis filtre les coups laissant le roi en échec.
 */
public class MoveGenerator {

    public MoveGenerator() {}

    /**
     * Retourne la liste de tous les coups légaux pour le camp qui doit jouer.
     */
    public static List<Move> generateLegalMoves(BitboardState state) {
        List<Move> pseudoLegal = generatePseudoLegalMoves(state);
        List<Move> legal = new ArrayList<>();
        for (Move move : pseudoLegal) {
            BitboardState after = applyMove(state, move);
            if (!isInCheck(after, state.getSideToMove())) {
                legal.add(move);
            }
        }
        return legal;
    }

    /**
     * Retourne tous les coups légaux depuis une case spécifique.
     */
    public static List<Move> generateLegalMovesFrom(BitboardState state, Square from) {
        return generateLegalMoves(state).stream()
            .filter(m -> m.from() == from)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * Vérifie si le camp donné est en échec dans l'état fourni.
     */
    public static boolean isInCheck(BitboardState state, Color color) {
        long kingBB = state.getBitboard(color, Piece.KING);
        if (kingBB == 0) return false;
        int kingSq = Bitboard.lsb(kingBB);
        return isSquareAttackedBy(state, kingSq, color.opposite());
    }

    /**
     * Vérifie si une case est attaquée par le camp indiqué.
     */
    public static boolean isSquareAttackedBy(BitboardState state, int squareIndex, Color attacker) {
        long occ = state.getAllOccupancy();
        long attackerPieces;

        // Pions
        attackerPieces = state.getBitboard(attacker, Piece.PAWN);
        long pawnAttacks = attacker == Color.WHITE
            ? AttackTables.WHITE_PAWN_ATTACKS[squareIndex]
            : AttackTables.BLACK_PAWN_ATTACKS[squareIndex];
        // On cherche si un pion adverse attaque cette case : on inverse
        // (les attaques depuis squareIndex vers les pions adverses)
        if (attacker == Color.WHITE) {
            if ((AttackTables.BLACK_PAWN_ATTACKS[squareIndex] & state.getBitboard(attacker, Piece.PAWN)) != 0) return true;
        } else {
            if ((AttackTables.WHITE_PAWN_ATTACKS[squareIndex] & state.getBitboard(attacker, Piece.PAWN)) != 0) return true;
        }

        // Cavaliers
        if ((AttackTables.KNIGHT_ATTACKS[squareIndex] & state.getBitboard(attacker, Piece.KNIGHT)) != 0)
            return true;

        // Roi
        if ((AttackTables.KING_ATTACKS[squareIndex] & state.getBitboard(attacker, Piece.KING)) != 0)
            return true;

        // Fous + Dames (diagonales)
        long bishopQueens = state.getBitboard(attacker, Piece.BISHOP) | state.getBitboard(attacker, Piece.QUEEN);
        if (getBishopAttacks(squareIndex, occ, 0L) != 0 &&
            (getBishopAttacks(squareIndex, occ, 0L) & bishopQueens) != 0)
            return true;

        // Tours + Dames (lignes)
        long rookQueens = state.getBitboard(attacker, Piece.ROOK) | state.getBitboard(attacker, Piece.QUEEN);
        if ((getRookAttacks(squareIndex, occ, 0L) & rookQueens) != 0)
            return true;

        return false;
    }

    // ── Génération pseudo-légale ──────────────────────────────────────────────

    private static List<Move> generatePseudoLegalMoves(BitboardState state) {
        Color color = state.getSideToMove();
        List<Move> moves = new ArrayList<>(60);

        generatePawnMoves(state, color, moves);
        generateKnightMoves(state, color, moves);
        generateBishopMoves(state, color, moves);
        generateRookMoves(state, color, moves);
        generateQueenMoves(state, color, moves);
        generateKingMoves(state, color, moves);
        generateCastlingMoves(state, color, moves);

        return moves;
    }

    // ── Pions ─────────────────────────────────────────────────────────────────

    private static void generatePawnMoves(BitboardState state, Color color, List<Move> moves) {
        long pawns = state.getBitboard(color, Piece.PAWN);
        long occ   = state.getAllOccupancy();
        long enemies = state.getOccupancy(color.opposite());

        boolean white = color == Color.WHITE;
        long promoRank = white ? Bitboard.RANK_8 : Bitboard.RANK_1;
        long startRank = white ? Bitboard.RANK_2 : Bitboard.RANK_7;

        while (pawns != 0) {
            int from = Bitboard.lsb(pawns);
            pawns = Bitboard.popLsb(pawns);
            long fromMask = 1L << from;

            // Poussée simple
            long push1 = white ? Bitboard.shiftNorth(fromMask) : Bitboard.shiftSouth(fromMask);
            push1 &= ~occ;

            if (push1 != 0) {
                int to = Bitboard.lsb(push1);
                if ((push1 & promoRank) != 0) {
                    addPromotions(from, to, moves);
                } else {
                    moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
                    // Poussée double depuis la rangée de départ
                    long push2 = white ? Bitboard.shiftNorth(push1) : Bitboard.shiftSouth(push1);
                    push2 &= ~occ & (white ? Bitboard.RANK_4 : Bitboard.RANK_5);
                    if ((fromMask & startRank) != 0 && push2 != 0) {
                        moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(Bitboard.lsb(push2))));
                    }
                }
            }

            // Prises
            long attacks = white
                ? AttackTables.WHITE_PAWN_ATTACKS[from]
                : AttackTables.BLACK_PAWN_ATTACKS[from];
            long captures = attacks & enemies;

            while (captures != 0) {
                int to = Bitboard.lsb(captures);
                captures = Bitboard.popLsb(captures);
                if ((1L << to & promoRank) != 0) {
                    addPromotions(from, to, moves);
                } else {
                    moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
                }
            }

            // En passant
            Square ep = state.getEnPassantTarget();
            if (ep != null && (attacks & ep.mask) != 0) {
                moves.add(Move.enPassant(Square.fromIndex(from), ep));
            }
        }
    }

    private static void addPromotions(int from, int to, List<Move> moves) {
        Square f = Square.fromIndex(from), t = Square.fromIndex(to);
        moves.add(Move.promotion(f, t, Piece.QUEEN));
        moves.add(Move.promotion(f, t, Piece.ROOK));
        moves.add(Move.promotion(f, t, Piece.BISHOP));
        moves.add(Move.promotion(f, t, Piece.KNIGHT));
    }

    // ── Cavaliers ────────────────────────────────────────────────────────────

    private static void generateKnightMoves(BitboardState state, Color color, List<Move> moves) {
        long knights = state.getBitboard(color, Piece.KNIGHT);
        long allies  = state.getOccupancy(color);
        while (knights != 0) {
            int from = Bitboard.lsb(knights);
            knights = Bitboard.popLsb(knights);
            long targets = AttackTables.KNIGHT_ATTACKS[from] & ~allies;
            addMovesFromTargets(from, targets, moves);
        }
    }

    // ── Fous ─────────────────────────────────────────────────────────────────

    private static void generateBishopMoves(BitboardState state, Color color, List<Move> moves) {
        long bishops = state.getBitboard(color, Piece.BISHOP);
        long allies  = state.getOccupancy(color);
        long occ     = state.getAllOccupancy();
        while (bishops != 0) {
            int from = Bitboard.lsb(bishops);
            bishops = Bitboard.popLsb(bishops);
            long targets = getBishopAttacks(from, occ, allies);
            addMovesFromTargets(from, targets, moves);
        }
    }

    // ── Tours ─────────────────────────────────────────────────────────────────

    private static void generateRookMoves(BitboardState state, Color color, List<Move> moves) {
        long rooks  = state.getBitboard(color, Piece.ROOK);
        long allies = state.getOccupancy(color);
        long occ    = state.getAllOccupancy();
        while (rooks != 0) {
            int from = Bitboard.lsb(rooks);
            rooks = Bitboard.popLsb(rooks);
            long targets = getRookAttacks(from, occ, allies);
            addMovesFromTargets(from, targets, moves);
        }
    }

    // ── Dames ─────────────────────────────────────────────────────────────────

    private static void generateQueenMoves(BitboardState state, Color color, List<Move> moves) {
        long queens = state.getBitboard(color, Piece.QUEEN);
        long allies = state.getOccupancy(color);
        long occ    = state.getAllOccupancy();
        while (queens != 0) {
            int from = Bitboard.lsb(queens);
            queens = Bitboard.popLsb(queens);
            long targets = getBishopAttacks(from, occ, allies) | getRookAttacks(from, occ, allies);
            addMovesFromTargets(from, targets, moves);
        }
    }

    // ── Roi ───────────────────────────────────────────────────────────────────

    private static void generateKingMoves(BitboardState state, Color color, List<Move> moves) {
        long king   = state.getBitboard(color, Piece.KING);
        long allies = state.getOccupancy(color);
        if (king == 0) return;
        int from = Bitboard.lsb(king);
        long targets = AttackTables.KING_ATTACKS[from] & ~allies;
        addMovesFromTargets(from, targets, moves);
    }

    // ── Roque ─────────────────────────────────────────────────────────────────

    private static void generateCastlingMoves(BitboardState state, Color color, List<Move> moves) {
        long occ = state.getAllOccupancy();
        Color enemy = color.opposite();

        if (color == Color.WHITE) {
            if (state.getCastlingRights().canWhiteKingside()
                && (occ & Bitboard.WHITE_KINGSIDE_BETWEEN) == 0
                && !isAnySquareAttacked(state, Bitboard.WHITE_KINGSIDE_KING_PATH, enemy)) {
                moves.add(Move.castling(Square.E1, Square.G1));
            }
            if (state.getCastlingRights().canWhiteQueenside()
                && (occ & Bitboard.WHITE_QUEENSIDE_BETWEEN) == 0
                && !isAnySquareAttacked(state, Bitboard.WHITE_QUEENSIDE_KING_PATH, enemy)) {
                moves.add(Move.castling(Square.E1, Square.C1));
            }
        } else {
            if (state.getCastlingRights().canBlackKingside()
                && (occ & Bitboard.BLACK_KINGSIDE_BETWEEN) == 0
                && !isAnySquareAttacked(state, Bitboard.BLACK_KINGSIDE_KING_PATH, enemy)) {
                moves.add(Move.castling(Square.E8, Square.G8));
            }
            if (state.getCastlingRights().canBlackQueenside()
                && (occ & Bitboard.BLACK_QUEENSIDE_BETWEEN) == 0
                && !isAnySquareAttacked(state, Bitboard.BLACK_QUEENSIDE_KING_PATH, enemy)) {
                moves.add(Move.castling(Square.E8, Square.C8));
            }
        }
    }

    private static boolean isAnySquareAttacked(BitboardState state, long mask, Color attacker) {
        long temp = mask;
        while (temp != 0) {
            int sq = Bitboard.lsb(temp);
            temp = Bitboard.popLsb(temp);
            if (isSquareAttackedBy(state, sq, attacker)) return true;
        }
        return false;
    }

    // ── Calcul d'attaques des pièces glissantes (par rayons) ──────────────────

    static long getBishopAttacks(int sq, long occ, long allies) {
        long attacks = 0L;
        attacks |= raySlide(sq, occ,  9, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ,  7, Bitboard.NOT_FILE_H);
        attacks |= raySlide(sq, occ, -7, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ, -9, Bitboard.NOT_FILE_H);
        return attacks & ~allies;
    }

    static long getRookAttacks(int sq, long occ, long allies) {
        long attacks = 0L;
        attacks |= raySlide(sq, occ,  8, 0xFFFFFFFFFFFFFFFFL);
        attacks |= raySlide(sq, occ, -8, 0xFFFFFFFFFFFFFFFFL);
        attacks |= raySlide(sq, occ,  1, Bitboard.NOT_FILE_A);
        attacks |= raySlide(sq, occ, -1, Bitboard.NOT_FILE_H);
        return attacks & ~allies;
    }

    /**
     * Calcule les cases attaquées par un glissement dans un rayon.
     * @param sq       case de départ
     * @param occ      occupation complète
     * @param shift    décalage (positif = gauche, négatif = droite)
     * @param noWrap   masque pour éviter le wrap-around de colonne
     */
    private static long raySlide(int sq, long occ, int shift, long noWrap) {
        long attacks = 0L;
        long current = 1L << sq;
        for (int i = 0; i < 7; i++) {
            if (shift > 0) current = (current << shift) & noWrap;
            else           current = (current >>> (-shift)) & noWrap;
            if (current == 0) break;
            attacks |= current;
            if ((current & occ) != 0) break;
        }
        return attacks;
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static void addMovesFromTargets(int from, long targets, List<Move> moves) {
        while (targets != 0) {
            int to = Bitboard.lsb(targets);
            targets = Bitboard.popLsb(targets);
            moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
        }
    }

    // ── Application d'un coup (pour la vérification de légalité) ─────────────

    /**
     * Applique un coup sur l'état et retourne le nouvel état.
     * Utilisé pour vérifier la légalité (le roi ne doit pas être en échec après).
     */
    public static BitboardState applyMove(BitboardState state, Move move) {
        Color color = state.getSideToMove();
        Color enemy = color.opposite();
        Square from = move.from();
        Square to   = move.to();

        BitboardState.PieceOnSquare pos = state.getPieceAt(from);
        if (pos == null) return state; // coup invalide

        Piece piece = pos.piece();
        BitboardState next = state.copy();

        // Retirer la pièce de la case de départ
        next = next.withoutPiece(color, piece, from);

        // Retirer une éventuelle pièce ennemie capturée
        if (move.isEnPassant()) {
            Square captured = color == Color.WHITE
                ? Square.fromIndex(to.index - 8)
                : Square.fromIndex(to.index + 8);
            next = next.withoutPiece(enemy, Piece.PAWN, captured);
        } else {
            BitboardState.PieceOnSquare target = state.getPieceAt(to);
            if (target != null && target.color() == enemy) {
                next = next.withoutPiece(enemy, target.piece(), to);
            }
        }

        // Poser la pièce sur la case d'arrivée (ou la pièce promue)
        Piece landing = move.isPromotion() ? move.promotionPiece() : piece;
        next = next.withPiece(color, landing, to);

        // Roque : déplacer aussi la tour
        if (move.isCastling()) {
            if (to == Square.G1) {
                next = next.withoutPiece(color, Piece.ROOK, Square.H1)
                           .withPiece(color, Piece.ROOK, Square.F1);
            } else if (to == Square.C1) {
                next = next.withoutPiece(color, Piece.ROOK, Square.A1)
                           .withPiece(color, Piece.ROOK, Square.D1);
            } else if (to == Square.G8) {
                next = next.withoutPiece(color, Piece.ROOK, Square.H8)
                           .withPiece(color, Piece.ROOK, Square.F8);
            } else if (to == Square.C8) {
                next = next.withoutPiece(color, Piece.ROOK, Square.A8)
                           .withPiece(color, Piece.ROOK, Square.D8);
            }
        }

        // Mise à jour de la case en passant
        Square newEp = null;
        if (piece == Piece.PAWN && Math.abs(to.rank - from.rank) == 2) {
            int epRank = (from.rank + to.rank) / 2;
            newEp = Square.fromIndex(from.file + epRank * 8);
        }
        next = next.withEnPassantTarget(newEp);

        // Mise à jour des droits de roque
        CastlingRights cr = state.getCastlingRights();
        if (piece == Piece.KING) {
            cr = cr.remove(color == Color.WHITE
                ? CastlingRights.WHITE_KINGSIDE | CastlingRights.WHITE_QUEENSIDE
                : CastlingRights.BLACK_KINGSIDE | CastlingRights.BLACK_QUEENSIDE);
        }
        if (from == Square.A1 || to == Square.A1) cr = cr.remove(CastlingRights.WHITE_QUEENSIDE);
        if (from == Square.H1 || to == Square.H1) cr = cr.remove(CastlingRights.WHITE_KINGSIDE);
        if (from == Square.A8 || to == Square.A8) cr = cr.remove(CastlingRights.BLACK_QUEENSIDE);
        if (from == Square.H8 || to == Square.H8) cr = cr.remove(CastlingRights.BLACK_KINGSIDE);
        next = next.withCastlingRights(cr);

        // Compteurs
        boolean isCapture = state.getPieceAt(to) != null || move.isEnPassant();
        int newHalfClock = (piece == Piece.PAWN || isCapture) ? 0 : state.getHalfMoveClock() + 1;
        int newFullMove  = state.getFullMoveNumber() + (color == Color.BLACK ? 1 : 0);

        next = next.withHalfMoveClock(newHalfClock).withFullMoveNumber(newFullMove);
        next = next.withSideToMove(enemy);

        return next;
    }
}
