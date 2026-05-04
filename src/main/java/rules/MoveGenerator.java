package rules;

import core.Bitboard;
import core.BitboardState;
import core.ZobristHasher;
import model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Génère tous les coups légaux depuis un état donné.
 * Utilise des bitboards pour la génération pseudo-légale,
 * puis filtre les coups laissant le roi en échec.
 *
 * Les attaques des pièces glissantes utilisent {@link MagicBitboards} — O(1).
 */
public class MoveGenerator {

    public MoveGenerator() {}

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

    public static List<Move> generateLegalMovesFrom(BitboardState state, Square from) {
        return generateLegalMoves(state).stream()
            .filter(m -> m.from() == from)
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    public static boolean isInCheck(BitboardState state, Color color) {
        long kingBB = state.getBitboard(color, Piece.KING);
        if (kingBB == 0) return false;
        int kingSq = Bitboard.lsb(kingBB);
        return isSquareAttackedBy(state, kingSq, color.opposite());
    }

    public static boolean isSquareAttackedBy(BitboardState state, int squareIndex, Color attacker) {
        long occ = state.getAllOccupancy();

        // Pions
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

        // Fous + Dames (diagonales) — Magic Bitboards O(1)
        long bishopQueens = state.getBitboard(attacker, Piece.BISHOP) | state.getBitboard(attacker, Piece.QUEEN);
        if (bishopQueens != 0 && (MagicBitboards.getBishopAttacks(squareIndex, occ) & bishopQueens) != 0)
            return true;

        // Tours + Dames (lignes) — Magic Bitboards O(1)
        long rookQueens = state.getBitboard(attacker, Piece.ROOK) | state.getBitboard(attacker, Piece.QUEEN);
        if ((MagicBitboards.getRookAttacks(squareIndex, occ) & rookQueens) != 0)
            return true;

        return false;
    }

    // ── Génération pseudo-légale ──────────────────────────────────────────────

    public static List<Move> generatePseudoLegalCaptures(BitboardState state) {
        Color color = state.getSideToMove();
        long enemies = state.getOccupancy(color.opposite());
        long occ = state.getAllOccupancy();
        long allies = state.getOccupancy(color);
        List<Move> captures = new ArrayList<>(16);

        long pawns = state.getBitboard(color, Piece.PAWN);
        boolean white = color == Color.WHITE;
        long promoRank = white ? Bitboard.RANK_8 : Bitboard.RANK_1;
        long tempPawns = pawns;
        while (tempPawns != 0) {
            int from = Bitboard.lsb(tempPawns);
            tempPawns = Bitboard.popLsb(tempPawns);
            long attacks = white
                ? AttackTables.WHITE_PAWN_ATTACKS[from]
                : AttackTables.BLACK_PAWN_ATTACKS[from];
            long pawnCaptures = attacks & enemies;
            while (pawnCaptures != 0) {
                int to = Bitboard.lsb(pawnCaptures);
                pawnCaptures = Bitboard.popLsb(pawnCaptures);
                if ((1L << to & promoRank) != 0) {
                    addPromotions(from, to, captures);
                } else {
                    captures.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
                }
            }
            Square ep = state.getEnPassantTarget();
            if (ep != null && (attacks & ep.mask) != 0) {
                captures.add(Move.enPassant(Square.fromIndex(from), ep));
            }
        }

        long knights = state.getBitboard(color, Piece.KNIGHT);
        while (knights != 0) {
            int from = Bitboard.lsb(knights);
            knights = Bitboard.popLsb(knights);
            long targets = AttackTables.KNIGHT_ATTACKS[from] & enemies;
            addMovesFromTargets(from, targets, captures);
        }

        long bishops = state.getBitboard(color, Piece.BISHOP);
        while (bishops != 0) {
            int from = Bitboard.lsb(bishops);
            bishops = Bitboard.popLsb(bishops);
            long targets = MagicBitboards.getBishopAttacks(from, occ) & enemies & ~allies;
            addMovesFromTargets(from, targets, captures);
        }

        long rooks = state.getBitboard(color, Piece.ROOK);
        while (rooks != 0) {
            int from = Bitboard.lsb(rooks);
            rooks = Bitboard.popLsb(rooks);
            long targets = MagicBitboards.getRookAttacks(from, occ) & enemies & ~allies;
            addMovesFromTargets(from, targets, captures);
        }

        long queens = state.getBitboard(color, Piece.QUEEN);
        while (queens != 0) {
            int from = Bitboard.lsb(queens);
            queens = Bitboard.popLsb(queens);
            long targets = MagicBitboards.getQueenAttacks(from, occ) & enemies & ~allies;
            addMovesFromTargets(from, targets, captures);
        }

        long king = state.getBitboard(color, Piece.KING);
        if (king != 0) {
            int from = Bitboard.lsb(king);
            long targets = AttackTables.KING_ATTACKS[from] & enemies;
            addMovesFromTargets(from, targets, captures);
        }

        return captures;
    }

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

            long push1 = white ? Bitboard.shiftNorth(fromMask) : Bitboard.shiftSouth(fromMask);
            push1 &= ~occ;

            if (push1 != 0) {
                int to = Bitboard.lsb(push1);
                if ((push1 & promoRank) != 0) {
                    addPromotions(from, to, moves);
                } else {
                    moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
                    long push2 = white ? Bitboard.shiftNorth(push1) : Bitboard.shiftSouth(push1);
                    push2 &= ~occ & (white ? Bitboard.RANK_4 : Bitboard.RANK_5);
                    if ((fromMask & startRank) != 0 && push2 != 0) {
                        moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(Bitboard.lsb(push2))));
                    }
                }
            }

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

    // ── Pièces — toutes via Magic Bitboards ──────────────────────────────────

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

    private static void generateBishopMoves(BitboardState state, Color color, List<Move> moves) {
        long bishops = state.getBitboard(color, Piece.BISHOP);
        long allies  = state.getOccupancy(color);
        long occ     = state.getAllOccupancy();
        while (bishops != 0) {
            int from = Bitboard.lsb(bishops);
            bishops = Bitboard.popLsb(bishops);
            long targets = MagicBitboards.getBishopAttacks(from, occ) & ~allies;
            addMovesFromTargets(from, targets, moves);
        }
    }

    private static void generateRookMoves(BitboardState state, Color color, List<Move> moves) {
        long rooks  = state.getBitboard(color, Piece.ROOK);
        long allies = state.getOccupancy(color);
        long occ    = state.getAllOccupancy();
        while (rooks != 0) {
            int from = Bitboard.lsb(rooks);
            rooks = Bitboard.popLsb(rooks);
            long targets = MagicBitboards.getRookAttacks(from, occ) & ~allies;
            addMovesFromTargets(from, targets, moves);
        }
    }

    private static void generateQueenMoves(BitboardState state, Color color, List<Move> moves) {
        long queens = state.getBitboard(color, Piece.QUEEN);
        long allies = state.getOccupancy(color);
        long occ    = state.getAllOccupancy();
        while (queens != 0) {
            int from = Bitboard.lsb(queens);
            queens = Bitboard.popLsb(queens);
            long targets = MagicBitboards.getQueenAttacks(from, occ) & ~allies;
            addMovesFromTargets(from, targets, moves);
        }
    }

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

    // ── Compatibilité — méthodes conservées pour AttackTables.rayAttacks ──────
    // (utilisées par d'autres modules éventuels)

    static long getBishopAttacks(int sq, long occ, long allies) {
        return MagicBitboards.getBishopAttacks(sq, occ) & ~allies;
    }

    static long getRookAttacks(int sq, long occ, long allies) {
        return MagicBitboards.getRookAttacks(sq, occ) & ~allies;
    }

    // ── Utilitaire ────────────────────────────────────────────────────────────

    private static void addMovesFromTargets(int from, long targets, List<Move> moves) {
        while (targets != 0) {
            int to = Bitboard.lsb(targets);
            targets = Bitboard.popLsb(targets);
            moves.add(Move.of(Square.fromIndex(from), Square.fromIndex(to)));
        }
    }

    // ── Application d'un coup ─────────────────────────────────────────────────

    public static BitboardState applyMove(BitboardState state, Move move) {
        Color color = state.getSideToMove();
        Color enemy = color.opposite();
        Square from = move.from();
        Square to   = move.to();

        BitboardState.PieceOnSquare pos = state.getPieceAt(from);
        if (pos == null) return state;

        Piece piece = pos.piece();
        int colorIdx = color.ordinal();
        int enemyIdx = enemy.ordinal();

        long[][] bbs = new long[2][6];
        System.arraycopy(state.getBitboardsRow(0), 0, bbs[0], 0, 6);
        System.arraycopy(state.getBitboardsRow(1), 0, bbs[1], 0, 6);

        long hash = state.getZobristHash();

        bbs[colorIdx][piece.index] &= ~from.mask;
        hash ^= ZobristHasher.PIECE_HASH[colorIdx][piece.index][from.index];

        if (move.isEnPassant()) {
            Square captured = color == Color.WHITE
                ? Square.fromIndex(to.index - 8)
                : Square.fromIndex(to.index + 8);
            bbs[enemyIdx][Piece.PAWN.index] &= ~captured.mask;
            hash ^= ZobristHasher.PIECE_HASH[enemyIdx][Piece.PAWN.index][captured.index];
        } else {
            for (int p = 0; p < 6; p++) {
                if ((bbs[enemyIdx][p] & to.mask) != 0) {
                    bbs[enemyIdx][p] &= ~to.mask;
                    hash ^= ZobristHasher.PIECE_HASH[enemyIdx][p][to.index];
                    break;
                }
            }
        }

        Piece landing = move.isPromotion() ? move.promotionPiece() : piece;
        bbs[colorIdx][landing.index] |= to.mask;
        hash ^= ZobristHasher.PIECE_HASH[colorIdx][landing.index][to.index];

        if (move.isCastling()) {
            Square rookFrom, rookTo;
            if      (to == Square.G1) { rookFrom = Square.H1; rookTo = Square.F1; }
            else if (to == Square.C1) { rookFrom = Square.A1; rookTo = Square.D1; }
            else if (to == Square.G8) { rookFrom = Square.H8; rookTo = Square.F8; }
            else                      { rookFrom = Square.A8; rookTo = Square.D8; }
            bbs[colorIdx][Piece.ROOK.index] &= ~rookFrom.mask;
            bbs[colorIdx][Piece.ROOK.index] |=  rookTo.mask;
            hash ^= ZobristHasher.PIECE_HASH[colorIdx][Piece.ROOK.index][rookFrom.index];
            hash ^= ZobristHasher.PIECE_HASH[colorIdx][Piece.ROOK.index][rookTo.index];
        }

        Square newEp = null;
        if (piece == Piece.PAWN && Math.abs(to.rank - from.rank) == 2) {
            int epRank = (from.rank + to.rank) / 2;
            newEp = Square.fromIndex(from.file + epRank * 8);
        }
        if (state.getEnPassantTarget() != null)
            hash ^= ZobristHasher.EN_PASSANT_HASH[state.getEnPassantTarget().file];
        if (newEp != null)
            hash ^= ZobristHasher.EN_PASSANT_HASH[newEp.file];

        CastlingRights cr = state.getCastlingRights();
        CastlingRights newCr = cr;
        if (piece == Piece.KING) {
            newCr = newCr.remove(color == Color.WHITE
                ? CastlingRights.WHITE_KINGSIDE | CastlingRights.WHITE_QUEENSIDE
                : CastlingRights.BLACK_KINGSIDE | CastlingRights.BLACK_QUEENSIDE);
        }
        if (from == Square.A1 || to == Square.A1) newCr = newCr.remove(CastlingRights.WHITE_QUEENSIDE);
        if (from == Square.H1 || to == Square.H1) newCr = newCr.remove(CastlingRights.WHITE_KINGSIDE);
        if (from == Square.A8 || to == Square.A8) newCr = newCr.remove(CastlingRights.BLACK_QUEENSIDE);
        if (from == Square.H8 || to == Square.H8) newCr = newCr.remove(CastlingRights.BLACK_KINGSIDE);
        if (newCr.getRights() != cr.getRights()) {
            hash ^= ZobristHasher.CASTLING_HASH[cr.getRights()];
            hash ^= ZobristHasher.CASTLING_HASH[newCr.getRights()];
        }

        boolean isCapture = move.isEnPassant() || (state.getPieceAt(to) != null);
        int newHalfClock = (piece == Piece.PAWN || isCapture) ? 0 : state.getHalfMoveClock() + 1;
        int newFullMove  = state.getFullMoveNumber() + (color == Color.BLACK ? 1 : 0);

        hash ^= ZobristHasher.SIDE_TO_MOVE_HASH;

        return BitboardState.ofApplied(bbs, enemy, newCr, newEp, newHalfClock, newFullMove, hash);
    }
}
