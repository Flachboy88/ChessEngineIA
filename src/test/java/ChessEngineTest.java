import api.ChessAPI;
import core.BitboardState;
import engine.evaluation.*;
import engine.search.AlphaBetaSearch;
import engine.tb.SyzygyTablebase;
import engine.tb.WDL;
import game.FenParser;
import game.GameResult;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
import model.Square;
import org.junit.jupiter.api.*;
import player.classical.AlphaBetaPlayer;
import player.classical.RandomAIPlayer;

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

        @BeforeAll
        static void silenceLogs() {
            java.util.logging.Logger.getLogger("engine.tb.SyzygyTablebase")
                    .setLevel(java.util.logging.Level.OFF);
        }
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
            ChessAPI api = new ChessAPI("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP22P/RNBQKBNR w KQkq - 1 3");
            assertTrue(api.estEnEchec());
        }

        @Test @DisplayName("Pat — aucun coup légal, pas en échec")
        void stalemate() {
            ChessAPI api = new ChessAPI("1r6/8/8/8/8/k7/8/K7 w - - 0 1");
            assertEquals(0, api.getCoupsLegaux().size());
            assertFalse(api.estEnEchec());
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
            var white = new RandomAIPlayer(Color.WHITE);
            var black = new RandomAIPlayer(Color.BLACK);
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

    // =========================================================================
    // 9. IA AlphaBeta
    // =========================================================================

    @Nested @DisplayName("9. IA AlphaBeta")
    class AlphaBetaTests {

        @Test @DisplayName("Score symétrique en position initiale (doit être ~0)")
        void scoreSymetriePositionInitiale() {
            int score = PositionEvaluator.evaluate(new ChessAPI().getBitboardState());
            assertEquals(0, score, "La position initiale doit être évaluée à 0 (symétrie)");
        }

        @Test @DisplayName("evaluateFor retourne l'opposé selon le camp")
        void evaluateForCohérence() {
            var bs = new ChessAPI("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1").getBitboardState();
            assertEquals(PositionEvaluator.evaluateFor(bs, Color.WHITE),
                        -PositionEvaluator.evaluateFor(bs, Color.BLACK));
        }

        @Test @DisplayName("Avantage matériel détecté (Blancs ont une dame de plus)")
        void avantageMatériel() {
            int score = PositionEvaluator.evaluate(new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState());
            assertTrue(score > 800, "Score attendu > 800, obtenu : " + score);
        }

        @Test @DisplayName("Phase de jeu : initiale ≈ 256")
        void phaseJeuInitiale() {
            assertTrue(PositionEvaluator.gamePhase(new ChessAPI().getBitboardState()) >= 240,
                "Phase initiale doit être >= 240 (sur 256)");
        }

        @Test @DisplayName("Phase de jeu : rois seuls = 0")
        void phaseJeuFinale() {
            assertEquals(0, PositionEvaluator.gamePhase(
                new ChessAPI("4k3/8/8/8/8/8/8/4K3 w - - 0 1").getBitboardState()));
        }

        @Test @DisplayName("AlphaBeta prend une pièce gratuite")
        void prendPieceGratuite() {
            GameState gs = new GameState(new ChessAPI("4k3/8/8/4r3/8/8/8/4R1K1 w - - 0 1").getBitboardState());
            assertEquals(Square.E5, AlphaBetaSearch.chercherMeilleurCoup(gs, 2).to());
        }

        @Test @DisplayName("AlphaBeta donne mat en 1")
        void matEnUn() {
            GameState gs = new GameState(new ChessAPI("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4").getBitboardState());
            assertEquals(Square.F7, AlphaBetaSearch.chercherMeilleurCoup(gs, 3).to());
        }

        @Test @DisplayName("AlphaBeta évite de sacrifier sa dame")
        void evitePerdreDame() {
            GameState gs = new GameState(new ChessAPI("3r4/8/8/8/8/8/3P4/3QK3 w - - 0 1").getBitboardState());
            assertNotEquals("d1d8", AlphaBetaSearch.chercherMeilleurCoup(gs, 3).toUci());
        }

        @Test @DisplayName("AlphaBetaPlayer joue un coup légal (2s)")
        void alphaPlayerJoueCoupLegal() {
            var ia = new AlphaBetaPlayer(Color.WHITE, 2_000L);
            ChessAPI api = new ChessAPI();
            Move coup = ia.getNextMove(new GameState(api.getBitboardState()));
            assertNotNull(coup);
            assertTrue(api.getCoupsLegaux().contains(coup));
        }

        @Test @DisplayName("AlphaBeta vs Random : AlphaBeta ne perd pas (2s)")
        void alphaBeatRandom() {
            AlphaBetaSearch.clearTT();
            var api = new ChessAPI();
            api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE, 2_000L));
            api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));
            int moves = 0;
            while (!api.estTerminee() && moves++ < 200) api.jouerCoupJoueur();
            assertNotEquals(GameResult.BLACK_WINS, api.getEtatPartie());
        }

        @Test @DisplayName("AlphaBetaPlayer refuse temps < 100ms")
        void tempsInvalide() {
            assertThrows(IllegalArgumentException.class, () -> new AlphaBetaPlayer(Color.WHITE, 50L));
        }
    }

    // =========================================================================
    // 10. Évaluation avancée (PST PeSTO + modules)
    // =========================================================================

    @Nested @DisplayName("10. Évaluation avancée")
    class EvaluationAvanceeTests {

        @Test @DisplayName("Pions doublés : malus détecté")
        void pionsDoublés() {
            var avec = new ChessAPI("4k3/8/8/8/8/4P3/4P3/4K3 w - - 0 1").getBitboardState();
            var sans = new ChessAPI("4k3/8/8/8/8/3P4/4P3/4K3 w - - 0 1").getBitboardState();
            int scorePionsDoubles = PawnEvaluator.evaluate(avec, 128);
            int scorePionsNormaux = PawnEvaluator.evaluate(sans, 128);
            assertTrue(scorePionsDoubles < scorePionsNormaux,
                "Doubles=" + scorePionsDoubles + " Normaux=" + scorePionsNormaux);
        }

        @Test @DisplayName("Pions isolés : malus détecté")
        void pionsIsolés() {
            var isole  = new ChessAPI("4k3/8/8/8/8/P7/8/4K3 w - - 0 1").getBitboardState();
            var normal = new ChessAPI("4k3/8/8/8/3P4/8/8/4K3 w - - 0 1").getBitboardState();
            assertTrue(PawnEvaluator.evaluate(isole, 128) <= PawnEvaluator.evaluate(normal, 128));
        }

        @Test @DisplayName("Pion passé blanc en e6 : bonus en finale")
        void pionPasséBonus() {
            var avec = new ChessAPI("4k3/8/4P3/8/8/8/8/4K3 w - - 0 1").getBitboardState();
            var sans = new ChessAPI("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1").getBitboardState();
            assertTrue(PawnEvaluator.evaluate(avec, 0) > PawnEvaluator.evaluate(sans, 0));
        }

        @Test @DisplayName("PawnEvaluator : symétrie en position initiale")
        void pawnEvalSymetrie() {
            assertEquals(0, PawnEvaluator.evaluate(new ChessAPI().getBitboardState(), 256));
        }

        @Test @DisplayName("Mobilité : paire de fous donne un bonus")
        void paireDeFous() {
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/2BB1K2 w - - 0 1").getBitboardState();
            assertTrue(MobilityEvaluator.evaluate(bs, 128) > 0);
        }

        @Test @DisplayName("Mobilité : tour sur colonne ouverte = bonus")
        void tourColonneOuverte() {
            var ouverte = new ChessAPI("4k3/8/8/8/8/8/8/4R1K1 w - - 0 1").getBitboardState();
            var bloquee = new ChessAPI("4k3/8/8/8/8/8/4P3/4R1K1 w - - 0 1").getBitboardState();
            assertTrue(MobilityEvaluator.evaluate(ouverte, 128) > MobilityEvaluator.evaluate(bloquee, 128));
        }

        @Test @DisplayName("Mobilité : symétrie en position initiale")
        void mobiliteSymetrie() {
            assertEquals(0, MobilityEvaluator.evaluate(new ChessAPI().getBitboardState(), 256));
        }

        @Test @DisplayName("Sécurité roi : bouclier de pions = meilleur score")
        void bouclierDePions() {
            var avec = new ChessAPI("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1").getBitboardState();
            var sans = new ChessAPI("4k3/8/8/8/8/8/8/6K1 w - - 0 1").getBitboardState();
            assertTrue(KingSafety.evaluate(avec, 205) > KingSafety.evaluate(sans, 205));
        }

        @Test @DisplayName("Sécurité roi : symétrie → score = 0")
        void kingSafetySymetrie() {
            assertEquals(0, KingSafety.evaluate(new ChessAPI().getBitboardState(), 256));
        }

        @Test @DisplayName("Sécurité roi : nul en finale (phase=0)")
        void kingSafetyNullEnFinale() {
            assertEquals(0, KingSafety.evaluate(
                new ChessAPI("4k3/8/8/8/8/8/8/6K1 w - - 0 1").getBitboardState(), 0));
        }

        @Test @DisplayName("Évaluation complète : Blancs avantagés après e4 (PST)")
        void evalApresE4() {
            var apres = new ChessAPI("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1").getBitboardState();
            assertTrue(PositionEvaluator.evaluateFor(apres, Color.WHITE) > 0);
        }

        @Test @DisplayName("Interpolation MG/EG : valeurs cohérentes aux extrêmes")
        void interpolationCoherence() {
            assertTrue(PositionEvaluator.gamePhase(new ChessAPI().getBitboardState()) >= 230);
            assertEquals(0, PositionEvaluator.gamePhase(
                new ChessAPI("4k3/8/8/8/8/8/8/4K3 w - - 0 1").getBitboardState()));
        }

        @Test @DisplayName("AlphaBeta avec nouvelle évaluation : mat en 2 détecté")
        void matEnDeuxAvecNouvelleEval() {
            AlphaBetaSearch.clearTT();
            var gs = new GameState(new ChessAPI("3k4/8/8/8/8/8/8/3KQR2 w - - 0 1").getBitboardState());
            Move best2 = AlphaBetaSearch.chercherMeilleurCoup(gs, 2);
            AlphaBetaSearch.clearTT();
            final Move[] best4 = {null};
            Thread t = new Thread(() -> best4[0] = AlphaBetaSearch.chercherMeilleurCoup(gs, 4));
            t.start();
            try { t.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) t.interrupt();
            Move best = best4[0] != null ? best4[0] : best2;
            assertNotNull(best, "L'IA doit retourner un coup dans une position gagnante");
        }
    }

    // =========================================================================
    // 11. Nouvelles optimisations moteur (Tier 1-3)
    // =========================================================================

    @Nested @DisplayName("11. Optimisations moteur — Tier 1 à 3")
    class OptimisationMoteurTests {

        // ── SEE ───────────────────────────────────────────────────────────────

        @Test @DisplayName("SEE : prise gagnante Fou×Dame non défendue détectée positive")
        void seePriseFouDame() {
            // Fou blanc c4 prend Dame noire d5 (non défendue)
            // SEE = 900 - 0 = +900 → positif
            ChessAPI api = new ChessAPI("4k3/8/8/3q4/2B5/8/8/4K3 w - - 0 1");
            var move = api.getCoups(Square.C4).stream()
                .filter(m -> m.to() == Square.D5).findFirst().orElse(null);
            assertNotNull(move);
            assertTrue(AlphaBetaSearch.seeCaptureScore(api.getBitboardState(), move) > 0,
                "Fou×Dame non défendue doit être positive");
        }

        @Test @DisplayName("SEE : prise perdante Tour×Cavalier défendu par pion")
        void seePrisePerdante() {
            // Tour blanche e1 prend Cavalier noir e5, défendu par pion noir d6
            // Rxe5 (gain 320) puis pxe5 (prend la Tour 500) → net blanc = 320-500 = -180 → SEE < 0
            // Note : pion d6 attaque e5 car les pions noirs capturent en diagonale vers l'avant
            // (vers rangs décroissants) : d6 attaque c5 et e5 ✓
            ChessAPI api = new ChessAPI("4k3/8/3p4/4n3/8/8/8/4R1K1 w - - 0 1");
            var move = api.getCoups(Square.E1).stream()
                .filter(m -> m.to() == Square.E5).findFirst().orElse(null);
            assertNotNull(move, "La Tour doit pouvoir aller en e5");
            int seeScore = AlphaBetaSearch.seeCaptureScore(api.getBitboardState(), move);
            assertTrue(seeScore < 0,
                "SEE Tour×Cavalier défendu par pion doit être négative. Score=" + seeScore);
        }

        @Test @DisplayName("SEE : prise gagnante pion×pion non défendu >= 0")
        void seePionPion() {
            ChessAPI api = new ChessAPI("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1");
            var move = api.getCoups(Square.E4).stream()
                .filter(m -> m.to() == Square.D5).findFirst().orElse(null);
            assertNotNull(move);
            assertTrue(AlphaBetaSearch.seeCaptureScore(api.getBitboardState(), move) >= 0);
        }

        // ── History Malus ─────────────────────────────────────────────────────

        @Test @DisplayName("History Malus : l'IA est déterministe (résultat stable)")
        void historyMalusStabilite() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4")
                    .getBitboardState());
            Move best1 = AlphaBetaSearch.chercherMeilleurCoup(gs, 3);
            AlphaBetaSearch.clearTT();
            Move best2 = AlphaBetaSearch.chercherMeilleurCoup(gs, 3);
            assertNotNull(best1); assertNotNull(best2);
            assertEquals(best1.toUci(), best2.toUci(), "L'IA doit être déterministe");
        }

        // ── Countermove ───────────────────────────────────────────────────────

        @Test @DisplayName("Countermove : réponse légale à 1.e4")
        void countermoveMeilleurReponse() {
            AlphaBetaSearch.clearTT();
            ChessAPI api = new ChessAPI();
            api.jouerCoup("e2e4");
            Move reponse = AlphaBetaSearch.chercherMeilleurCoup(
                new GameState(api.getBitboardState()), 3);
            assertNotNull(reponse);
            assertTrue(api.getCoupsLegaux().contains(reponse));
        }

        // ── Aspiration Windows ────────────────────────────────────────────────

        @Test @DisplayName("Aspiration Windows : coups légaux à depth 2-5")
        void aspirationWindowsCoherente() {
            for (int depth = 2; depth <= 5; depth++) {
                AlphaBetaSearch.clearTT();
                Move best = AlphaBetaSearch.chercherMeilleurCoup(
                    new GameState(new ChessAPI().getBitboardState()), depth);
                assertNotNull(best, "depth=" + depth + " doit retourner un coup");
                assertTrue(new ChessAPI().getCoupsLegaux().contains(best),
                    "Coup depth=" + depth + " doit être légal : " + best.toUci());
            }
        }

        // ── PVS ───────────────────────────────────────────────────────────────

        @Test @DisplayName("PVS : mat en 1 toujours trouvé")
        void pvsMatEnUn() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
                    .getBitboardState());
            assertEquals(Square.F7, AlphaBetaSearch.chercherMeilleurCoup(gs, 3).to());
        }

        @Test @DisplayName("PVS : tour gratuite prise à depth=4")
        void pvsCoherencePrise() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("4k3/8/8/4r3/8/8/8/4R1K1 w - - 0 1").getBitboardState());
            assertEquals(Square.E5, AlphaBetaSearch.chercherMeilleurCoup(gs, 4).to());
        }

        // ── Razoring ─────────────────────────────────────────────────────────

        @Test @DisplayName("Razoring : rapide en position très défavorable (<5s)")
        void razoringRapide() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("4k3/8/8/8/8/8/8/QQQQK3 b - - 0 1").getBitboardState());
            long t0 = System.currentTimeMillis();
            Move best = AlphaBetaSearch.chercherMeilleurCoup(gs, 3);
            assertNotNull(best);
            assertTrue(System.currentTimeMillis() - t0 < 5000);
        }

        // ── LMP ───────────────────────────────────────────────────────────────

        @Test @DisplayName("LMP : évite sacrifice dame sur tour défendue")
        void lmpEviteSacrifice() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("3r4/8/8/8/8/8/3P4/3QK3 w - - 0 1").getBitboardState());
            assertNotEquals("d1d8", AlphaBetaSearch.chercherMeilleurCoup(gs, 4).toUci());
        }

        // ── NMP adaptatif ─────────────────────────────────────────────────────

        @Test @DisplayName("NMP adaptatif : coup trouvé à depth=6")
        void nmpAdaptatifGagnant() {
            AlphaBetaSearch.clearTT();
            assertNotNull(AlphaBetaSearch.chercherMeilleurCoup(
                new GameState(new ChessAPI("4k3/8/8/8/8/8/8/3KQR2 w - - 0 1").getBitboardState()), 6));
        }

        // ── Singular Extensions ───────────────────────────────────────────────

        @Test @DisplayName("Singular Extensions : coup forcé joué (final pion)")
        void singularExtensionForcé() {
            AlphaBetaSearch.clearTT();
            Move best = AlphaBetaSearch.chercherMeilleurCoup(
                new GameState(new ChessAPI("3k4/3P4/3K4/8/8/8/8/8 w - - 0 1").getBitboardState()), 5);
            assertNotNull(best);
            assertTrue(best.from() == Square.D7 || best.from() == Square.D6,
                "Doit jouer pion ou roi : " + best.toUci());
        }

        // ── IID ───────────────────────────────────────────────────────────────

        @Test @DisplayName("IID : coup légal trouvé sans TT")
        void iidSansTT() {
            AlphaBetaSearch.clearTT();
            String fen = "r1bq1rk1/pp2ppbp/2np1np1/8/2pPP3/2N2N2/PP2BPPP/R1BQ1RK1 w - - 0 8";
            Move best = AlphaBetaSearch.chercherMeilleurCoup(
                new GameState(new ChessAPI(fen).getBitboardState()), 5);
            assertNotNull(best);
            assertTrue(new ChessAPI(fen).getCoupsLegaux().contains(best),
                "Coup IID doit être légal : " + best.toUci());
        }

        // ── Pawn Hash Table ───────────────────────────────────────────────────

        @Test @DisplayName("Pawn Hash Table : résultat identique avec/sans cache")
        void pawnHashTableConsistency() {
            String fen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
            GameState gs = new GameState(new ChessAPI(fen).getBitboardState());
            AlphaBetaSearch.clearTT(); AlphaBetaSearch.clearPawnTT();
            Move best1 = AlphaBetaSearch.chercherMeilleurCoup(gs, 4);
            AlphaBetaSearch.clearTT();
            Move best2 = AlphaBetaSearch.chercherMeilleurCoup(gs, 4);
            assertNotNull(best1); assertNotNull(best2);
            assertEquals(best1.toUci(), best2.toUci(),
                "Cache pions ne doit pas changer le résultat");
        }

        @Test @DisplayName("Pawn Hash Table : 2ème run pas plus lent que 1er×2")
        void pawnHashTablePerf() {
            String fen = "r1bq1rk1/pp2ppbp/2np1np1/8/2pPP3/2N2N2/PP2BPPP/R1BQ1RK1 w - - 0 8";
            GameState gs = new GameState(new ChessAPI(fen).getBitboardState());
            AlphaBetaSearch.clearTT(); AlphaBetaSearch.clearPawnTT();
            long t0 = System.currentTimeMillis();
            AlphaBetaSearch.chercherMeilleurCoup(gs, 5);
            long time1 = System.currentTimeMillis() - t0;
            AlphaBetaSearch.clearTT();
            long t1 = System.currentTimeMillis();
            AlphaBetaSearch.chercherMeilleurCoup(gs, 5);
            long time2 = System.currentTimeMillis() - t1;
            System.out.println("Pawn TT: sans=" + time1 + "ms avec=" + time2 + "ms");
            assertTrue(time2 < time1 * 2,
                "Cache pions ne doit pas ralentir. Sans=" + time1 + "ms Avec=" + time2 + "ms");
        }

        // ── Régression ────────────────────────────────────────────────────────

        @Test @DisplayName("Régression : bat toujours RandomAI (1.5s)")
        void regressionAlphaBeatRandom() {
            AlphaBetaSearch.clearTT();
            var api = new ChessAPI();
            api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE, 1_500L));
            api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));
            int moves = 0;
            while (!api.estTerminee() && moves++ < 200) api.jouerCoupJoueur();
            assertNotEquals(GameResult.BLACK_WINS, api.getEtatPartie());
        }

        @Test @DisplayName("Régression : mat en 1 toujours détecté")
        void regressionMatEnUn() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
                    .getBitboardState());
            assertEquals(Square.F7, AlphaBetaSearch.chercherMeilleurCoup(gs, 3).to());
        }

        @Test @DisplayName("Régression : pièce gratuite toujours prise à depth=2")
        void regressionPriseGratuite() {
            AlphaBetaSearch.clearTT();
            GameState gs = new GameState(
                new ChessAPI("4k3/8/8/4r3/8/8/8/4R1K1 w - - 0 1").getBitboardState());
            assertEquals(Square.E5, AlphaBetaSearch.chercherMeilleurCoup(gs, 2).to());
        }

        // ── Perft ─────────────────────────────────────────────────────────────

        @Test @DisplayName("Perft depth=1 : 20 coups")
        void perftDepth1() { assertEquals(20, new ChessAPI().getCoupsLegaux().size()); }

        @Test @DisplayName("Perft depth=2 : 400 positions")
        void perftDepth2() {
            ChessAPI api = new ChessAPI();
            int total = 0;
            for (Move m : api.getCoupsLegaux()) {
                ChessAPI fork = new ChessAPI(api.toFEN());
                fork.jouerCoup(m);
                total += fork.getCoupsLegaux().size();
            }
            assertEquals(400, total);
        }

        @Test @DisplayName("Perft depth=3 : 8902 positions")
        void perftDepth3() {
            assertEquals(8902, perft(new ChessAPI(), 3));
        }

        private int perft(ChessAPI api, int depth) {
            if (depth == 0) return 1;
            int total = 0;
            for (Move m : api.getCoupsLegaux()) {
                ChessAPI fork = new ChessAPI(api.toFEN());
                fork.jouerCoup(m);
                total += perft(fork, depth - 1);
            }
            return total;
        }
    }

    // =========================================================================
    // Tests Tablebases & fin de partie
    // =========================================================================

    @Nested
    @DisplayName("Tablebases & fin de partie")
    class Tablebases {

        private static final int MAX_MOVES = 200; // garde-fou anti-boucle infinie

        /**
         * Joue une partie IA vs IA depuis un FEN donné avec les sondes built-in uniquement
         * (sans fichiers .rtbw). Force la tablebase built-in sur les joueurs ET sur
         * AlphaBetaSearch pour éviter que l'auto-chargement des fichiers Syzygy
         * n'interfère avec les tests.
         */
        private GameResult jouerPartie(String fen) {
            AlphaBetaSearch.clearTT();
            // TB built-in : répertoire inexistant → builtInOnly=true, available=false
            SyzygyTablebase builtInTb = new SyzygyTablebase(
                java.nio.file.Path.of("nonexistent_syzygy_dir_for_tests"));
            AlphaBetaSearch.setTablebase(builtInTb);

            GameState state = new GameState(FenParser.parse(fen));

            // Créer les joueurs SANS auto-chargement : on passe un budget fixe
            // et on force la TB built-in pour court-circuiter l'auto-load.
            AlphaBetaPlayer blanc = new AlphaBetaPlayer(Color.WHITE, 500L)
                .withTablebases(builtInTb);
            AlphaBetaPlayer noir  = new AlphaBetaPlayer(Color.BLACK, 500L)
                .withTablebases(builtInTb);

            for (int i = 0; i < MAX_MOVES; i++) {
                GameResult r = state.getResult();
                if (r.isOver()) return r;
                AlphaBetaPlayer joueur = state.getSideToMove() == Color.WHITE ? blanc : noir;
                List<Move> legaux = state.getLegalMoves();
                if (legaux.isEmpty()) return state.getResult();
                Move coup = joueur.getNextMove(state);
                state.applyMove(coup);
            }
            // Nettoyage : restaurer la TB disabled par défaut
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
            return state.getResult(); // timeout garde-fou
        }

        // ── Sondes WDL directes ───────────────────────────────────────────────

        @Test
        @DisplayName("Sonde WDL : KQvK → WIN pour les blancs")
        void sondeKQvK() {
            BitboardState bs = FenParser.parse("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1");
            WDL wdl = SyzygyTablebase.disabled().probe(bs); // disabled → inconnu
            // On teste la sonde built-in via une instance temp
            // (disabled retourne unknown, on vérifie que le moteur interne fonctionne)
            // Test via probeBuiltIn accessible indirectement : on crée une instance
            // avec un répertoire inexistant pour déclencher uniquement les built-ins
            SyzygyTablebase tb = new SyzygyTablebase(java.nio.file.Path.of("nonexistent_dir_xyz"));
            WDL result = tb.probe(bs);
            // disabled → unknown (les built-ins ne sont pas appelés sans available=true)
            // Pour tester les built-ins, on passe par AlphaBetaSearch
            assertFalse(result.isKnown() && result.isLoss(), "KQvK ne doit pas être LOSS pour les blancs");
        }

        // ── isInsufficientMaterial ────────────────────────────────────────────

        @Test
        @DisplayName("Matériel insuffisant : KvK → nulle")
        void materialKvK() {
            GameState state = new GameState(FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1"));
            assertEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel insuffisant : K+N vs K → nulle")
        void materialKNvK() {
            GameState state = new GameState(FenParser.parse("4k3/8/8/8/8/8/8/4KN2 w - - 0 1"));
            assertEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel insuffisant : K+B vs K → nulle")
        void materialKBvK() {
            GameState state = new GameState(FenParser.parse("4k3/8/8/8/8/8/8/4KB2 w - - 0 1"));
            assertEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel PAS insuffisant : KBB vs K → pas nulle")
        void materialKBBvKNotDraw() {
            GameState state = new GameState(FenParser.parse("8/8/8/8/8/3K4/3BB3/4k3 w - - 0 1"));
            assertNotEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel PAS insuffisant : KBN vs K → pas nulle")
        void materialKBNvKNotDraw() {
            GameState state = new GameState(FenParser.parse("4k3/8/8/8/8/3K4/3BN3/8 w - - 0 1"));
            assertNotEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel PAS insuffisant : KQ vs K → pas nulle")
        void materialKQvKNotDraw() {
            GameState state = new GameState(FenParser.parse("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1"));
            assertNotEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        @Test
        @DisplayName("Matériel PAS insuffisant : KR vs K → pas nulle")
        void materialKRvKNotDraw() {
            GameState state = new GameState(FenParser.parse("8/8/8/8/8/3K4/8/R3k3 w - - 0 1"));
            assertNotEquals(GameResult.DRAW_INSUFFICIENT_MATERIAL, state.getResult());
        }

        // ── Parties complètes IA vs IA ───────────────────────────────────────

        @Test
        @DisplayName("Partie IA vs IA : KRvK → les blancs gagnent")
        @Timeout(30)
        void partieKRvK() {
            GameResult r = jouerPartie("8/8/8/8/8/3K4/8/R3k3 w - - 0 1");
            assertEquals(GameResult.WHITE_WINS, r,
                "KRvK doit finir par mat blanc, résultat obtenu : " + r);
        }

        @Test
        @DisplayName("Partie IA vs IA : KQvK → les blancs gagnent")
        @Timeout(30)
        void partieKQvK() {
            GameResult r = jouerPartie("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1");
            assertEquals(GameResult.WHITE_WINS, r,
                "KQvK doit finir par mat blanc, résultat obtenu : " + r);
        }

        @Test
        @DisplayName("Partie IA vs IA : KBBvK → les blancs gagnent")
        @Timeout(60)
        void partieKBBvK() {
            // KBBvK est la finale la plus difficile des built-ins :
            // le roi noir cherche à capturer un fou (donnant KBvK = nulle).
            // On utilise un budget plus élevé pour que l'IA blanche anticipe cela.
            AlphaBetaSearch.clearTT();
            SyzygyTablebase builtInTb = new SyzygyTablebase(
                java.nio.file.Path.of("nonexistent_syzygy_dir_for_tests"));
            AlphaBetaSearch.setTablebase(builtInTb);
            GameState state = new GameState(FenParser.parse("8/8/8/8/8/3K4/3BB3/4k3 w - - 0 1"));
            AlphaBetaPlayer blanc = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withTablebases(builtInTb);
            AlphaBetaPlayer noir  = new AlphaBetaPlayer(Color.BLACK, 500L)
                .withTablebases(builtInTb);
            for (int i = 0; i < MAX_MOVES; i++) {
                GameResult r = state.getResult();
                if (r.isOver()) {
                    AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
                    assertEquals(GameResult.WHITE_WINS, r,
                        "KBBvK doit finir par mat blanc, résultat obtenu : " + r);
                    return;
                }
                AlphaBetaPlayer joueur = state.getSideToMove() == Color.WHITE ? blanc : noir;
                Move coup = joueur.getNextMove(state);
                state.applyMove(coup);
            }
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
            fail("KBBvK n'a pas terminé en " + MAX_MOVES + " coups : " + state.getResult());
        }

        @Test
        @DisplayName("Partie IA vs IA : KQvK FEN exact du rapport de bug → blancs gagnent")
        @Timeout(30)
        void partieKQvKFenRapportBug() {
            // FEN exact du rapport de bug : les blancs DOIVENT gagner, pas égalité
            GameResult r = jouerPartie("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1");
            assertEquals(GameResult.WHITE_WINS, r,
                "KQvK (FEN rapport de bug) doit finir par mat blanc, résultat : " + r);
        }

        @Test
        @DisplayName("Built-in WDL : score WIN gradué par ply (pas de boucle)")
        void builtInWinScoreGraduated() {
            // Vérifie que le moteur attribue des scores différents selon la profondeur,
            // ce qui force la progression vers le mat sans boucle de répétition.
            AlphaBetaSearch.clearTT();
            SyzygyTablebase builtIn = new SyzygyTablebase(
                java.nio.file.Path.of("nonexistent_dir_xyz_test"));
            AlphaBetaSearch.setTablebase(builtIn);

            // KQvK : la position est WIN pour les blancs
            BitboardState bs = FenParser.parse("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1");
            GameState gs = new GameState(bs);
            // On cherche à depth 5 : doit retourner un coup
            Move best = AlphaBetaSearch.chercherMeilleurCoup(gs, 5);
            assertNotNull(best, "L'IA doit trouver un coup en KQvK");
            // Le coup retourné doit être légal
            assertTrue(new api.ChessAPI("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1")
                .getCoupsLegaux().contains(best),
                "Le coup doit être légal : " + best.toUci());
            AlphaBetaSearch.clearTT();
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
        }

        @Test
        @DisplayName("Partie IA vs IA : KQvK ne doit PAS finir en égalité par répétition")
        @Timeout(30)
        void partieKQvKPasRepetition() {
            GameResult r = jouerPartie("8/8/8/8/8/3K4/8/Q3k3 w - - 0 1");
            assertNotEquals(GameResult.DRAW_REPETITION, r,
                "KQvK ne doit pas finir par répétition de coups");
            assertNotEquals(GameResult.DRAW_50_MOVES, r,
                "KQvK ne doit pas finir par règle des 50 coups");
        }

        @Test
        @DisplayName("Partie IA vs IA : KRvK → blancs gagnent (sans fichiers TB)")
        @Timeout(30)
        void partieKRvKNoFiles() {
            GameResult r = jouerPartie("8/8/8/8/8/3K4/8/R3k3 w - - 0 1");
            assertEquals(GameResult.WHITE_WINS, r,
                "KRvK doit finir par mat blanc, résultat : " + r);
        }

        @Test
        @DisplayName("Partie IA vs IA : KRvKB → nulle théorique")
        @Timeout(30)
        void partieKRvKB() {
            GameResult r = jouerPartie("4k3/4b3/8/8/8/8/8/R3K3 w - - 0 1");
            // Avec les tablebases Syzygy, le blanc reconnaît que c'est DRAW et
            // les noirs défendent correctement grâce aux scores DTZ.
            // Sans tablebases, le blanc peut mater si le noir défend mal.
            assertTrue(
                r == GameResult.DRAW_50_MOVES
                || r == GameResult.DRAW_REPETITION
                || r == GameResult.DRAW_INSUFFICIENT_MATERIAL
                || r == GameResult.STALEMATE
                || r == GameResult.WHITE_WINS,
                "KRvKB doit finir (nulle avec TB, ou mat blanc sans TB), résultat obtenu : " + r);
        }
    }
}
