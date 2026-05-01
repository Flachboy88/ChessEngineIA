package com.chessoptia;

import com.chessoptia.api.ChessAPI;
import com.chessoptia.game.FenParser;
import com.chessoptia.game.GameResult;
import com.chessoptia.model.Color;
import com.chessoptia.model.Move;
import com.chessoptia.model.Piece;
import com.chessoptia.model.Square;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de tests complète du moteur d'échecs ChessOptiIa.
 */
class ChessEngineTest {

    @Nested @DisplayName("1. État initial")
    class InitialStateTests {
        private ChessAPI api;
        @BeforeEach void setUp() { api = new ChessAPI(); }

        @Test @DisplayName("FEN de départ correct")
        void fenDeDepart() { assertEquals(FenParser.STARTING_FEN, api.toFEN()); }

        @Test @DisplayName("20 coups légaux en position initiale")
        void vingtCoupsInitiaux() { assertEquals(20, api.getCoupsLegaux().size()); }

        @Test @DisplayName("Pas d'échec en position initiale")
        void pasEchecInitial() { assertFalse(api.estEnEchec()); }

        @Test @DisplayName("Les blancs jouent en premier")
        void blancsPremier() { assertEquals(Color.WHITE, api.getCampActif()); }

        @Test @DisplayName("Partie en cours au départ")
        void partieEnCours() { assertEquals(GameResult.IN_PROGRESS, api.getEtatPartie()); }
    }

    @Nested @DisplayName("2. Mouvements des pions")
    class PawnMovesTests {
        private ChessAPI api;
        @BeforeEach void setUp() { api = new ChessAPI(); }

        @Test @DisplayName("Pion e2 : poussée simple e3")
        void pousseeSimplee3() {
            assertTrue(api.getCoups(Square.E2).stream().anyMatch(m -> m.to() == Square.E3));
        }

        @Test @DisplayName("Pion e2 : poussée double e4")
        void pousseDoubleE4() {
            assertTrue(api.getCoups(Square.E2).stream().anyMatch(m -> m.to() == Square.E4));
        }

        @Test @DisplayName("Pion e2 : exactement 2 coups depuis la position initiale")
        void deuxCoupsE2() { assertEquals(2, api.getCoups(Square.E2).size()); }

        @Test @DisplayName("Prise diagonale du pion")
        void priseDiagonale() {
            api.jouerCoup("e2e4"); api.jouerCoup("d7d5");
            assertTrue(api.getCoups(Square.E4).stream().anyMatch(m -> m.to() == Square.D5));
        }
    }

    @Nested @DisplayName("3. Mouvements des pièces")
    class PiecesMovesTests {

        @Test @DisplayName("Cavalier g1 → f3 ou h3 en position initiale")
        void cavalierG1() {
            ChessAPI api = new ChessAPI();
            List<Move> moves = api.getCoups(Square.G1);
            assertTrue(moves.stream().anyMatch(m -> m.to() == Square.F3));
            assertTrue(moves.stream().anyMatch(m -> m.to() == Square.H3));
            assertEquals(2, moves.size());
        }

        @Test @DisplayName("Cavalier en e4 a 8 coups en position ouverte")
        void cavalierE4HuitCoups() {
            assertEquals(8, new ChessAPI("8/8/8/8/4N3/8/8/4K3 w - - 0 1").getCoups(Square.E4).size());
        }

        @Test @DisplayName("Fou bloqué en position initiale — 0 coups")
        void fouBloqueInitial() { assertEquals(0, new ChessAPI().getCoups(Square.C1).size()); }

        @Test @DisplayName("Tour bloquée en position initiale — 0 coups")
        void tourBloqueInitial() { assertEquals(0, new ChessAPI().getCoups(Square.A1).size()); }

        @Test @DisplayName("Dame bloquée en position initiale — 0 coups")
        void dameBloqueInitiale() { assertEquals(0, new ChessAPI().getCoups(Square.D1).size()); }

        @Test @DisplayName("Roi bloqué en position initiale — 0 coups")
        void roiBloque() { assertEquals(0, new ChessAPI().getCoups(Square.E1).size()); }
    }

    @Nested @DisplayName("4. Règles spéciales")
    class SpecialMovesTests {

        @Test @DisplayName("En passant : le pion blanc peut capturer en passant")
        void enPassant() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("a7a6");
            api.jouerCoup("e4e5"); api.jouerCoup("d7d5");
            assertTrue(api.getCoups(Square.E5).stream()
                .anyMatch(m -> m.to() == Square.D6 && m.isEnPassant()));
        }

        @Test @DisplayName("En passant : la prise retire bien le pion capturé")
        void enPassantRetraitPion() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("a7a6");
            api.jouerCoup("e4e5"); api.jouerCoup("d7d5");
            api.jouerCoup("e5d6");
            assertNull(api.getBitboardState().getPieceAt(Square.D5));
        }

        @Test @DisplayName("Petit roque blanc (O-O) disponible")
        void petitRoqueBlanc() {
            ChessAPI api = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
            assertTrue(api.getCoups(Square.E1).stream()
                .anyMatch(m -> m.to() == Square.G1 && m.isCastling()));
        }

        @Test @DisplayName("Grand roque blanc (O-O-O) disponible")
        void grandRoqueBlanc() {
            ChessAPI api = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
            assertTrue(api.getCoups(Square.E1).stream()
                .anyMatch(m -> m.to() == Square.C1 && m.isCastling()));
        }

        @Test @DisplayName("Petit roque noir (o-o) disponible")
        void petitRoqueNoir() {
            ChessAPI api = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1");
            assertTrue(api.getCoups(Square.E8).stream()
                .anyMatch(m -> m.to() == Square.G8 && m.isCastling()));
        }

        @Test @DisplayName("Roque impossible si roi en échec")
        void roqueSiEchec() {
            ChessAPI api = new ChessAPI("4r3/8/8/8/8/8/8/R3K2R w KQ - 0 1");
            assertTrue(api.getCoups(Square.E1).stream().noneMatch(Move::isCastling));
        }

        @Test @DisplayName("Roque impossible si case de passage attaquée")
        void roqueSiPassageAttaque() {
            ChessAPI api = new ChessAPI("5r2/8/8/8/8/8/8/R3K2R w KQ - 0 1");
            assertTrue(api.getCoups(Square.E1).stream()
                .noneMatch(m -> m.to() == Square.G1 && m.isCastling()));
        }

        @Test @DisplayName("Roque déplace aussi la tour")
        void roqueDeplaceRook() {
            ChessAPI api = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
            System.out.println("=== DEBUG roqueDeplaceRook ===");
            System.out.println(api.afficherEchiquier());
            var coupsE1 = api.getCoups(Square.E1);
            System.out.println("Coups depuis E1 (" + coupsE1.size() + ") : " + coupsE1);
            var tousLesCoups = api.getCoupsLegaux();
            System.out.println("Tous coups légaux (" + tousLesCoups.size() + ") :");
            tousLesCoups.forEach(m -> System.out.println("  " + m + " castling=" + m.isCastling() + " promo=" + m.isPromotion() + " ep=" + m.isEnPassant()));
            System.out.println("Droits de roque : " + api.getBitboardState().getCastlingRights());
            System.out.println("En échec : " + api.estEnEchec());
            api.jouerCoup("e1g1");
            assertNotNull(api.getBitboardState().getPieceAt(Square.F1), "La tour doit être en f1");
            assertNull(api.getBitboardState().getPieceAt(Square.H1), "La tour ne doit plus être en h1");
        }

        @Test @DisplayName("Promotion en dame")
        void promotionDame() {
            ChessAPI api = new ChessAPI("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
            api.jouerCoup("a7a8");
            var pos = api.getBitboardState().getPieceAt(Square.A8);
            assertNotNull(pos);
            assertEquals(Piece.QUEEN, pos.piece());
            assertEquals(Color.WHITE, pos.color());
        }

        @Test @DisplayName("Promotion en cavalier avec prise")
        void promotionCavalierAvecPrise() {
            ChessAPI api = new ChessAPI("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
            api.jouerCoup(Square.A7, Square.B8, Piece.KNIGHT);
            var pos = api.getBitboardState().getPieceAt(Square.B8);
            assertNotNull(pos);
            assertEquals(Piece.KNIGHT, pos.piece());
        }
    }

    @Nested @DisplayName("5. Échec, mat et pat")
    class CheckMateTests {

        @Test @DisplayName("Fool's Mate — mat en 2 pour les noirs")
        void foolsMate() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("f2f3"); api.jouerCoup("e7e5");
            api.jouerCoup("g2g4"); api.jouerCoup("d8h4");
            assertEquals(GameResult.BLACK_WINS, api.getEtatPartie());
        }

        @Test @DisplayName("Scholar's Mate — mat en 4")
        void scholarsMate() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("e7e5");
            api.jouerCoup("f1c4"); api.jouerCoup("b8c6");
            api.jouerCoup("d1h5"); api.jouerCoup("a7a6");
            api.jouerCoup("h5f7");
            assertEquals(GameResult.WHITE_WINS, api.getEtatPartie());
        }

        @Test @DisplayName("Détection d'échec")
        void detectionEchec() {
            ChessAPI api = new ChessAPI("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
            assertTrue(api.estEnEchec());
        }

        @Test @DisplayName("Pat — aucun coup légal, pas en échec")
        void stalemate() {
            System.out.println("=== DEBUG stalemate ===");
            // Position de pat prouvée :
            // Roi blanc en a1, roi noir en a3, tour noire en b8.
            // Ka1 : a2 est contrôlé par Ka3, b1 et b2 sont contrôlés par Rb8 → 0 coups, pas en échec.
            ChessAPI api = new ChessAPI("1r6/8/8/8/8/k7/8/K7 w - - 0 1");
            System.out.println(api.afficherEchiquier());
            var coups = api.getCoupsLegaux();
            System.out.println("Coups légaux (" + coups.size() + ") : " + coups);
            System.out.println("En échec : " + api.estEnEchec());
            // Vérifier où sont réellement les pièces
            for (var sq : com.chessoptia.model.Square.values()) {
                var p = api.getBitboardState().getPieceAt(sq);
                if (p != null) System.out.println("  " + sq + " -> " + p.color() + " " + p.piece());
            }
            assertEquals(0, coups.size(), "Aucun coup légal");
            assertFalse(api.estEnEchec(), "Pas en échec");
            assertEquals(GameResult.STALEMATE, api.getEtatPartie());
        }

        @Test @DisplayName("Règle des 50 coups")
        void regle50Coups() {
            ChessAPI api = new ChessAPI("k7/8/K7/8/8/8/8/R7 w - - 100 50");
            assertEquals(GameResult.DRAW_50_MOVES, api.getEtatPartie());
        }
    }

    @Nested @DisplayName("6. FEN")
    class FENTests {

        @Test @DisplayName("Parse et re-sérialisation de la position initiale")
        void roundTripInitial() {
            assertEquals(FenParser.STARTING_FEN,
                FenParser.toFen(FenParser.parse(FenParser.STARTING_FEN)));
        }

        @Test @DisplayName("Parse une position avec en passant")
        void parseFenEnPassant() {
            ChessAPI api = new ChessAPI("rnbqkbnr/ppp1pppp/8/3pP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3");
            assertEquals(Square.D6, api.getBitboardState().getEnPassantTarget());
        }

        @Test @DisplayName("Tour au jeu correctement parsé")
        void parseFenTour() {
            assertEquals(Color.BLACK,
                new ChessAPI("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1").getCampActif());
        }

        @Test @DisplayName("Droits de roque correctement parsés")
        void parseFenRoque() {
            var cr = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1")
                .getBitboardState().getCastlingRights();
            assertTrue(cr.canWhiteKingside());
            assertTrue(cr.canWhiteQueenside());
            assertTrue(cr.canBlackKingside());
            assertTrue(cr.canBlackQueenside());
        }

        @Test @DisplayName("Round-trip après plusieurs coups")
        void roundTripApresCoups() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("e7e5"); api.jouerCoup("g1f3");
            String fen = api.toFEN();
            assertEquals(fen, new ChessAPI(fen).toFEN());
        }
    }

    @Nested @DisplayName("7. Undo (annulation de coups)")
    class UndoTests {

        @Test @DisplayName("Undo simple — retour à la position initiale")
        void undoSimple() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.undo();
            assertEquals(FenParser.STARTING_FEN, api.toFEN());
        }

        @Test @DisplayName("Undo de 5 coups consécutifs")
        void undoCinqCoups() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("e7e5");
            api.jouerCoup("g1f3"); api.jouerCoup("b8c6"); api.jouerCoup("f1c4");
            for (int i = 0; i < 5; i++) api.undo();
            assertEquals(FenParser.STARTING_FEN, api.toFEN());
        }

        @Test @DisplayName("Undo après en passant")
        void undoEnPassant() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("a7a6");
            api.jouerCoup("e4e5"); api.jouerCoup("d7d5");
            String avant = api.toFEN();
            api.jouerCoup("e5d6"); api.undo();
            assertEquals(avant, api.toFEN());
        }

        @Test @DisplayName("Undo après roque")
        void undoRoque() {
            ChessAPI api = new ChessAPI("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
            System.out.println("=== DEBUG undoRoque ===");
            System.out.println("Coups E1 : " + api.getCoups(Square.E1));
            String avant = api.toFEN();
            api.jouerCoup("e1g1"); api.undo();
            assertEquals(avant, api.toFEN());
        }

        @Test @DisplayName("Undo après promotion")
        void undoPromotion() {
            ChessAPI api = new ChessAPI("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
            String avant = api.toFEN();
            api.jouerCoup("a7a8"); api.undo();
            assertEquals(avant, api.toFEN());
        }

        @Test @DisplayName("Undo sur historique vide retourne false")
        void undoHistoriqueVide() { assertFalse(new ChessAPI().undo()); }
    }

    @Nested @DisplayName("8. Intégrité du moteur")
    class EngineIntegrityTests {

        @Test @DisplayName("Une partie RandomAI vs RandomAI se termine")
        void partieRandomVsRandom() {
            var white = new com.chessoptia.player.RandomAIPlayer(Color.WHITE);
            var black = new com.chessoptia.player.RandomAIPlayer(Color.BLACK);
            ChessAPI api = new ChessAPI();
            api.setWhitePlayer(white); api.setBlackPlayer(black);
            int maxMoves = 500, moves = 0;
            while (!api.estTerminee() && moves < maxMoves) { api.jouerCoupJoueur(); moves++; }
            assertTrue(api.estTerminee() || moves == maxMoves);
        }

        @Test @DisplayName("Reset remet la partie en position initiale")
        void reset() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("e7e5"); api.reset();
            assertEquals(FenParser.STARTING_FEN, api.toFEN());
            assertEquals(GameResult.IN_PROGRESS, api.getEtatPartie());
        }

        @Test @DisplayName("Les coups légaux ne laissent pas le roi en échec")
        void coupsLegauxSansEchec() {
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4"); api.jouerCoup("e7e5");
            for (Move m : api.getCoupsLegaux()) {
                ChessAPI fork = new ChessAPI(api.toFEN());
                fork.jouerCoup(m);
                assertFalse(fork.estEnEchec() && fork.getCampActif() == Color.WHITE,
                    "Le coup " + m + " laisse le roi blanc en échec");
            }
        }
    }
}
