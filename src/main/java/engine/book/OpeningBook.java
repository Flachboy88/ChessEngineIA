package engine.book;

import core.BitboardState;
import model.Color;
import model.Move;
import model.Piece;
import model.Square;
import rules.MoveGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Livre d'ouvertures au format Polyglot.
 *
 * <h2>Responsabilités</h2>
 * <ol>
 *   <li>Calculer la clé Polyglot d'une position {@link BitboardState}.</li>
 *   <li>Interroger le {@link PolyglotReader} pour récupérer les coups candidats.</li>
 *   <li>Convertir l'encodage Polyglot 16-bit en {@link Move} du moteur.</li>
 *   <li>Sélectionner un coup aléatoirement au prorata des poids (weighted random).</li>
 * </ol>
 *
 * <h2>Clé Zobrist Polyglot</h2>
 * Polyglot utilise une table de 781 valeurs 64-bit distincte de celle du moteur.
 * Les 768 premières valeurs encodent les pièces (12 types × 64 cases),
 * puis 4 pour le roque, 8 pour l'en passant, 1 pour le trait.
 * Source : <a href="http://hardy.uhasselt.be/Toga/book_format.html">Polyglot spec</a>
 *
 * <h2>Encodage des pièces Polyglot</h2>
 * Index = pieceType * 2 + colorBit, avec :
 * <pre>
 *   pion=0  cavalier=1  fou=2  tour=3  dame=4  roi=5
 *   noir→colorBit=0   blanc→colorBit=1
 * </pre>
 *
 * <h2>Weighted random</h2>
 * On choisit un coup proportionnellement à son poids (fréquence jouée).
 * Diversifie l'ouverture tout en favorisant les coups principaux.
 */
public final class OpeningBook {

    private static final Logger LOG = Logger.getLogger(OpeningBook.class.getName());

    private final PolyglotReader reader;
    private final Random         rng;

    // ── Offsets dans la table Polyglot ────────────────────────────────────────
    private static final int POLY_PIECE_OFFSET  = 0;    // 768 valeurs
    private static final int POLY_CASTLE_OFFSET = 768;  // 4 valeurs : K Q k q
    private static final int POLY_EP_OFFSET     = 772;  // 8 valeurs : colonnes a-h
    private static final int POLY_TURN_OFFSET   = 780;  // 1 valeur  : trait blanc

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Charge un fichier Polyglot (.bin).
     *
     * @param bookPath chemin vers le fichier .bin
     * @throws IOException si le fichier est introuvable ou illisible
     */
    public OpeningBook(Path bookPath) throws IOException {
        this(bookPath, new Random());
    }

    /** Constructeur avec graine fixe (tests déterministes). */
    public OpeningBook(Path bookPath, Random rng) throws IOException {
        this.reader = new PolyglotReader(bookPath);
        this.rng    = rng;
        LOG.info("Livre d'ouvertures : " + reader.getCount() + " entrées depuis " + bookPath);
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Cherche un coup de livre pour la position donnée.
     *
     * @param state position courante
     * @return coup sélectionné, ou {@code Optional.empty()} si hors livre
     */
    public Optional<Move> probe(BitboardState state) {
        long key = polyglotKey(state);
        List<PolyglotReader.PolyglotEntry> entries = reader.getEntries(key);
        if (entries.isEmpty()) return Optional.empty();

        PolyglotReader.PolyglotEntry chosen = weightedRandom(entries);
        if (chosen == null) return Optional.empty();

        return Optional.ofNullable(decodeMove(chosen.move16(), state));
    }

    /**
     * Calcule la clé Polyglot d'une position.
     * Exposé pour les tests.
     */
    public long polyglotKey(BitboardState state) {
        long key = 0L;

        // Pièces
        for (int ci = 0; ci < 2; ci++) {
            Color color = (ci == 0) ? Color.WHITE : Color.BLACK;
            for (Piece piece : Piece.values()) {
                long bb  = state.getBitboard(color, piece);
                int  idx = polyPieceIndex(piece, color);
                while (bb != 0) {
                    int sq = Long.numberOfTrailingZeros(bb);
                    bb &= bb - 1;
                    key ^= PolyglotZobrist.RANDOM[POLY_PIECE_OFFSET + idx * 64 + sq];
                }
            }
        }

        // Droits de roque (K Q k q)
        var cr = state.getCastlingRights();
        if (cr.canWhiteKingside())  key ^= PolyglotZobrist.RANDOM[POLY_CASTLE_OFFSET];
        if (cr.canWhiteQueenside()) key ^= PolyglotZobrist.RANDOM[POLY_CASTLE_OFFSET + 1];
        if (cr.canBlackKingside())  key ^= PolyglotZobrist.RANDOM[POLY_CASTLE_OFFSET + 2];
        if (cr.canBlackQueenside()) key ^= PolyglotZobrist.RANDOM[POLY_CASTLE_OFFSET + 3];

        // En passant (seulement si un pion peut réellement capturer)
        Square ep = state.getEnPassantTarget();
        if (ep != null && canEnPassantCapture(state, ep)) {
            key ^= PolyglotZobrist.RANDOM[POLY_EP_OFFSET + ep.file];
        }

        // Trait
        if (state.getSideToMove() == Color.WHITE) {
            key ^= PolyglotZobrist.RANDOM[POLY_TURN_OFFSET];
        }

        return key;
    }

    /** Nombre d'entrées dans le livre (utile pour les tests). */
    public int size() { return reader.getCount(); }

    // ── Weighted random ───────────────────────────────────────────────────────

    private PolyglotReader.PolyglotEntry weightedRandom(List<PolyglotReader.PolyglotEntry> entries) {
        int total = 0;
        for (var e : entries) total += e.weight();
        if (total <= 0) return entries.get(0);
        int pick = rng.nextInt(total), cumul = 0;
        for (var e : entries) {
            cumul += e.weight();
            if (pick < cumul) return e;
        }
        return entries.get(entries.size() - 1);
    }

    // ── Décodage du coup Polyglot ─────────────────────────────────────────────

    /**
     * Convertit un coup Polyglot 16-bit en {@link Move} du moteur.
     * Retourne null si le coup n'est pas légal (protection hash collision).
     *
     * Encodage Polyglot (bits) :
     *   0-5  : case d'arrivée   (to_file + to_rank*8)
     *   6-11 : case de départ   (from_file + from_rank*8)
     *   12-14: pièce promotion  (0=rien, 1=N, 2=B, 3=R, 4=Q)
     */
    private Move decodeMove(int move16, BitboardState state) {
        int toIdx   = (move16)       & 0x3F;
        int fromIdx = (move16 >> 6)  & 0x3F;
        int promoId = (move16 >> 12) & 0x07;

        // Correction roque : Polyglot encode roi→tour, le moteur encode roi→case_roi
        toIdx = fixCastlingTarget(fromIdx, toIdx);

        Square from  = Square.fromIndex(fromIdx);
        Square to    = Square.fromIndex(toIdx);
        Piece  promo = decodePromo(promoId);

        List<Move> legals = MoveGenerator.generateLegalMoves(state);
        for (Move m : legals) {
            if (m.from() != from || m.to() != to) continue;
            if (promo != null) {
                if (m.isPromotion() && m.promotionPiece() == promo) return m;
            } else {
                if (!m.isPromotion()) return m;
            }
        }
        return null;
    }

    /**
     * Polyglot encode le roque comme roi→tour d'origine.
     * Le moteur encode roi→case d'arrivée du roi.
     */
    private static int fixCastlingTarget(int from, int to) {
        if (from == 4  && to == 7)  return 6;  // O-O  blanc  : e1→h1 → e1→g1
        if (from == 4  && to == 0)  return 2;  // O-O-O blanc : e1→a1 → e1→c1
        if (from == 60 && to == 63) return 62; // O-O  noir   : e8→h8 → e8→g8
        if (from == 60 && to == 56) return 58; // O-O-O noir  : e8→a8 → e8→c8
        return to;
    }

    private static Piece decodePromo(int promoId) {
        return switch (promoId) {
            case 1 -> Piece.KNIGHT;
            case 2 -> Piece.BISHOP;
            case 3 -> Piece.ROOK;
            case 4 -> Piece.QUEEN;
            default -> null;
        };
    }

    private static int polyPieceIndex(Piece piece, Color color) {
        int type = switch (piece) {
            case PAWN   -> 0;
            case KNIGHT -> 1;
            case BISHOP -> 2;
            case ROOK   -> 3;
            case QUEEN  -> 4;
            case KING   -> 5;
        };
        return type * 2 + (color == Color.WHITE ? 1 : 0);
    }

    /**
     * Polyglot n'inclut l'en passant dans la clé que si un pion du camp actif
     * peut effectivement capturer. Sinon la clé diverge.
     */
    private static boolean canEnPassantCapture(BitboardState state, Square ep) {
        Color stm    = state.getSideToMove();
        long  pawns  = state.getBitboard(stm, Piece.PAWN);
        int   epFile = ep.file;
        int   epRank = ep.rank;
        if (epFile > 0 && (pawns & (1L << ((epFile - 1) + epRank * 8))) != 0) return true;
        if (epFile < 7 && (pawns & (1L << ((epFile + 1) + epRank * 8))) != 0) return true;
        return false;
    }
}
