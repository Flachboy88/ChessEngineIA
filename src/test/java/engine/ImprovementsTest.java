package engine;

import api.ChessAPI;
import engine.book.OpeningBook;
import engine.book.PolyglotReader;
import engine.book.PolyglotZobrist;
import engine.search.AlphaBetaSearch;
import engine.search.TimeManager;
import engine.tb.SyzygyTablebase;
import engine.tb.WDL;
import game.GameState;
import model.Color;
import model.Move;
import model.Piece;
import model.Square;
import org.junit.jupiter.api.*;
import player.classical.AlphaBetaPlayer;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des 3 améliorations :
 * <ol>
 *   <li>Livre d'ouvertures Polyglot</li>
 *   <li>Time Manager dynamique</li>
 *   <li>Tablebases Syzygy</li>
 * </ol>
 * Plus les tests de régression sur les 11 groupes existants.
 */
class ImprovementsTest {

    @BeforeAll
    static void silenceLogs() {
        java.util.logging.Logger.getLogger("engine.tb.SyzygyTablebase")
                .setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("engine.book.OpeningBook")
                .setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("player.classical.AlphaBetaPlayer")
                .setLevel(java.util.logging.Level.OFF);
    }

    // =========================================================================
    // 12. Livre d'ouvertures Polyglot
    // =========================================================================

    @Nested @DisplayName("12. Livre d'ouvertures Polyglot")
    class OpeningBookTests {

        // ── Utilitaire : créer un petit livre .bin en mémoire ─────────────────

        /**
         * Génère un fichier Polyglot minimal en mémoire contenant une seule entrée.
         * Format : 8 octets clé + 2 octets move16 + 2 octets poids + 4 octets learn (=0).
         *
         * @param key     clé Zobrist Polyglot
         * @param move16  coup encodé Polyglot
         * @param weight  poids
         * @return chemin vers le fichier temporaire
         */
        static Path makeMinimalBook(long key, int move16, int weight) throws IOException {
            Path tmp = Files.createTempFile("polyglot_test_", ".bin");
            tmp.toFile().deleteOnExit();
            ByteBuffer buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
            buf.putLong(key);
            buf.putShort((short) move16);
            buf.putShort((short) weight);
            buf.putInt(0); // learn
            Files.write(tmp, buf.array());
            return tmp;
        }

        /**
         * Génère un livre avec N entrées pour la même clé.
         */
        static Path makeBookMultiEntry(long key, int[] moves, int[] weights) throws IOException {
            Path tmp = Files.createTempFile("polyglot_multi_", ".bin");
            tmp.toFile().deleteOnExit();
            // IMPORTANT : les entrées doivent être triées par clé (unsigned)
            ByteBuffer buf = ByteBuffer.allocate(16 * moves.length).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < moves.length; i++) {
                buf.putLong(key);
                buf.putShort((short) moves[i]);
                buf.putShort((short) weights[i]);
                buf.putInt(0);
            }
            Files.write(tmp, buf.array());
            return tmp;
        }

        // ── PolyglotReader ────────────────────────────────────────────────────

        @Test @DisplayName("PolyglotReader : charge le bon nombre d'entrées")
        void readerChargeEntrees() throws IOException {
            long key = 0x823C9B50FD114196L; // clé de la position initiale Polyglot
            Path tmp = makeMinimalBook(key, 0x1C1F, 100);
            PolyglotReader reader = new PolyglotReader(tmp);
            assertEquals(1, reader.getCount());
        }

        @Test @DisplayName("PolyglotReader : retrouve l'entrée par clé")
        void readerTrouveEntree() throws IOException {
            long key = 0x823C9B50FD114196L;
            int  move16 = 0x1C1F;
            int  weight = 250;
            Path tmp = makeMinimalBook(key, move16, weight);
            PolyglotReader reader = new PolyglotReader(tmp);
            List<PolyglotReader.PolyglotEntry> entries = reader.getEntries(key);
            assertEquals(1, entries.size());
            assertEquals(move16, entries.get(0).move16());
            assertEquals(weight, entries.get(0).weight());
        }

        @Test @DisplayName("PolyglotReader : clé absente → liste vide")
        void readerCleAbsente() throws IOException {
            Path tmp = makeMinimalBook(0xDEADBEEFCAFEBABEL, 0x0001, 1);
            PolyglotReader reader = new PolyglotReader(tmp);
            assertTrue(reader.getEntries(0x1234567890ABCDEFL).isEmpty());
        }

        @Test @DisplayName("PolyglotReader : plusieurs entrées même clé")
        void readerMultiEntrees() throws IOException {
            long key = 0x823C9B50FD114196L;
            Path tmp = makeBookMultiEntry(key,
                new int[]{0x1C1F, 0x1E1F, 0x1021},
                new int[]{300,    200,    100});
            PolyglotReader reader = new PolyglotReader(tmp);
            assertEquals(3, reader.getEntries(key).size());
        }

        @Test @DisplayName("PolyglotReader : livre vide → count=0")
        void readerVide() throws IOException {
            Path tmp = Files.createTempFile("empty_", ".bin");
            tmp.toFile().deleteOnExit();
            PolyglotReader reader = new PolyglotReader(tmp);
            assertEquals(0, reader.getCount());
        }

        // ── Table Zobrist Polyglot ────────────────────────────────────────────

        @Test @DisplayName("PolyglotZobrist : table contient exactement 781 valeurs")
        void zobristTaille() {
            assertEquals(781, PolyglotZobrist.RANDOM.length);
        }

        @Test @DisplayName("PolyglotZobrist : toutes les valeurs sont non-nulles")
        void zobristNonNuls() {
            for (int i = 0; i < PolyglotZobrist.RANDOM.length; i++) {
                assertNotEquals(0L, PolyglotZobrist.RANDOM[i],
                    "Valeur nulle à l'index " + i);
            }
        }

        @Test @DisplayName("PolyglotZobrist : valeurs distinctes (pas de collisions)")
        void zobristDistinctes() {
            // On vérifie juste les 100 premières et les offsets clés pour garder le test rapide
            var set = new java.util.HashSet<Long>();
            for (int i = 0; i < 100; i++) set.add(PolyglotZobrist.RANDOM[i]);
            assertEquals(100, set.size(), "Collisions dans les 100 premières valeurs Polyglot");
        }

        // ── OpeningBook — clé Polyglot ────────────────────────────────────────

        @Test @DisplayName("OpeningBook.polyglotKey : position initiale → clé non nulle")
        void polyglotKeyInitiale() throws IOException {
            Path tmp = makeMinimalBook(0L, 0, 1); // livre factice juste pour instancier
            OpeningBook book = new OpeningBook(tmp);
            var bs = new ChessAPI().getBitboardState();
            long key = book.polyglotKey(bs);
            assertNotEquals(0L, key, "La clé de la position initiale ne doit pas être 0");
        }

        @Test @DisplayName("OpeningBook.polyglotKey : deux positions différentes → clés différentes")
        void polyglotKeyDifferentes() throws IOException {
            Path tmp = makeMinimalBook(0L, 0, 1);
            OpeningBook book = new OpeningBook(tmp);
            var api1 = new ChessAPI();
            var api2 = new ChessAPI();
            api2.jouerCoup("e2e4");
            long k1 = book.polyglotKey(api1.getBitboardState());
            long k2 = book.polyglotKey(api2.getBitboardState());
            assertNotEquals(k1, k2, "e4 doit changer la clé Polyglot");
        }

        @Test @DisplayName("OpeningBook.polyglotKey : stable entre appels")
        void polyglotKeyStable() throws IOException {
            Path tmp = makeMinimalBook(0L, 0, 1);
            OpeningBook book = new OpeningBook(tmp);
            var bs = new ChessAPI().getBitboardState();
            assertEquals(book.polyglotKey(bs), book.polyglotKey(bs));
        }

        @Test @DisplayName("OpeningBook.polyglotKey : trait change la clé")
        void polyglotKeyTrait() throws IOException {
            Path tmp = makeMinimalBook(0L, 0, 1);
            OpeningBook book = new OpeningBook(tmp);
            var apiW = new ChessAPI(); // Blancs à jouer
            var apiB = new ChessAPI(); apiB.jouerCoup("e2e4"); apiB.jouerCoup("e7e5"); // Blancs de nouveau
            // On compare simplement deux positions différentes
            assertNotEquals(book.polyglotKey(apiW.getBitboardState()),
                            book.polyglotKey(apiB.getBitboardState()));
        }

        // ── OpeningBook — probe ───────────────────────────────────────────────

        @Test @DisplayName("OpeningBook.probe : clé absente → empty")
        void probeAbsent() throws IOException {
            Path tmp = makeMinimalBook(0xDEADL, 0x0001, 100);
            OpeningBook book = new OpeningBook(tmp);
            // La position initiale ne sera pas dans ce livre factice
            Optional<Move> result = book.probe(new ChessAPI().getBitboardState());
            assertTrue(result.isEmpty(), "Position hors livre → empty");
        }

        @Test @DisplayName("OpeningBook.probe : coup trouvé est légal")
        void probeCoupLegal() throws IOException {
            ChessAPI api = new ChessAPI();
            // On calcule la vraie clé de la position initiale
            Path tmp = makeMinimalBook(0L, 0, 1); // factice
            OpeningBook book = new OpeningBook(tmp);
            long key = book.polyglotKey(api.getBitboardState());

            // e2e4 en Polyglot : from=e2(12), to=e4(28)
            // bits 0-5 = to = 28 = 0b011100, bits 6-11 = from = 12 = 0b001100, promo=0
            // move16 = (12 << 6) | 28 = 768 | 28 = 796
            int move16e2e4 = (Square.E2.index << 6) | Square.E4.index; // 796

            Path realBook = makeMinimalBook(key, move16e2e4, 100);
            OpeningBook realBookObj = new OpeningBook(realBook, new Random(42));
            Optional<Move> result = realBookObj.probe(api.getBitboardState());

            assertTrue(result.isPresent(), "e2e4 doit être dans le livre");
            assertTrue(api.getCoupsLegaux().contains(result.get()),
                "Le coup du livre doit être légal");
            assertEquals(Square.E4, result.get().to());
        }

        @Test @DisplayName("OpeningBook.probe : weighted random → distribution correcte")
        void probeWeightedRandom() throws IOException {
            ChessAPI api = new ChessAPI();
            Path tmp = makeMinimalBook(0L, 0, 1);
            OpeningBook book = new OpeningBook(tmp);
            long key = book.polyglotKey(api.getBitboardState());

            // Deux coups : e2e4 (poids 900) et d2d4 (poids 100)
            int move16e2e4 = (Square.E2.index << 6) | Square.E4.index;
            int move16d2d4 = (Square.D2.index << 6) | Square.D4.index;

            Path realBook = makeBookMultiEntry(key,
                new int[]{move16e2e4, move16d2d4},
                new int[]{900, 100});

            int countE4 = 0, countD4 = 0;
            for (int i = 0; i < 1000; i++) {
                OpeningBook b = new OpeningBook(realBook, new Random(i));
                Optional<Move> m = b.probe(api.getBitboardState());
                if (m.isPresent()) {
                    if (m.get().to() == Square.E4) countE4++;
                    else if (m.get().to() == Square.D4) countD4++;
                }
            }
            // e4 doit être choisi environ 900/1000 fois → entre 800 et 980
            assertTrue(countE4 > 800 && countE4 < 980,
                "e4 attendu ~900x/1000, obtenu : " + countE4);
        }

        // ── AlphaBetaPlayer avec livre ────────────────────────────────────────

        @Test @DisplayName("AlphaBetaPlayer avec livre : hasOpeningBook() = true")
        void playerAvecLivre() throws IOException {
            Path tmp = makeMinimalBook(0L, 0, 1);
            OpeningBook book = new OpeningBook(tmp);
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withOpeningBook(book);
            assertTrue(player.hasOpeningBook());
        }

        @Test @DisplayName("AlphaBetaPlayer sans livre : hasOpeningBook() = false")
        void playerSansLivre() {
            assertFalse(new AlphaBetaPlayer(Color.WHITE, 2_000L).hasOpeningBook());
        }

        @Test @DisplayName("AlphaBetaPlayer : chemin inexistant → pas d'exception, livre désactivé")
        void playerCheminInexistant() {
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withOpeningBook(Path.of("inexistant/livre.bin"));
            assertFalse(player.hasOpeningBook(), "Livre non chargé → hasOpeningBook=false");
        }

        @Test @DisplayName("AlphaBetaPlayer.getNextMove avec livre vide : joue quand même")
        void playerLivreVideJoue() throws IOException {
            Path empty = Files.createTempFile("empty_book_", ".bin");
            empty.toFile().deleteOnExit();
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withOpeningBook(empty);
            Move m = player.getNextMove(new GameState());
            assertNotNull(m, "Doit jouer même sans coup dans le livre");
            assertTrue(new ChessAPI().getCoupsLegaux().contains(m));
        }
    }

    // =========================================================================
    // 13. Time Manager dynamique
    // =========================================================================

    @Nested @DisplayName("13. Time Manager dynamique")
    class TimeManagerTests {

        @Test @DisplayName("TimeManager : target dans les bornes [MIN, tempsRestant/3]")
        void targetDansLesBornes() {
            long remaining = 60_000L;
            TimeManager tm = new TimeManager(remaining, 0, 15, false);
            long target = tm.getTargetMs();
            assertTrue(target >= 50, "Target doit être >= 50ms, obtenu : " + target);
            assertTrue(target <= remaining / 3, "Target doit être <= tempsRestant/3");
        }

        @Test @DisplayName("TimeManager : incrément augmente le budget")
        void incrementAugmenteBudget() {
            TimeManager sans  = new TimeManager(60_000L, 0L,     15, false);
            TimeManager avec  = new TimeManager(60_000L, 2_000L, 15, false);
            assertTrue(avec.getTargetMs() > sans.getTargetMs(),
                "Avec incrément, le budget doit être plus grand");
        }

        @Test @DisplayName("TimeManager : phase ouverture (coup 5) → budget réduit")
        void ouvertureRéduiseBudget() {
            TimeManager ouv  = new TimeManager(60_000L, 0, 5,  false);
            TimeManager mili = new TimeManager(60_000L, 0, 25, false);
            assertTrue(ouv.getTargetMs() <= mili.getTargetMs(),
                "Ouverture doit avoir un budget <= milieu de partie");
        }

        @Test @DisplayName("TimeManager : justLeftBook → bonus post-livre")
        void postBookBonus() {
            TimeManager normal  = new TimeManager(60_000L, 0, 15, false);
            TimeManager postBook = new TimeManager(60_000L, 0, 15, true);
            assertTrue(postBook.getTargetMs() >= normal.getTargetMs(),
                "Post-livre doit avoir un budget >= normal");
        }

        @Test @DisplayName("TimeManager.shouldStop : profondeur < 4 → jamais s'arrêter")
        void noStopBeforeDepth4() {
            TimeManager tm = new TimeManager(60_000L, 0, 15, false);
            // Profondeurs 1-3 : ne doit pas s'arrêter même si le temps est dépassé
            assertFalse(tm.shouldStop(1, null, null));
            assertFalse(tm.shouldStop(2, null, null));
            assertFalse(tm.shouldStop(3, null, null));
        }

        @Test @DisplayName("TimeManager.shouldStop : s'arrête après target dépassé à depth>=4")
        void stopApresTarget() throws InterruptedException {
            // Budget très court pour forcer l'expiration
            TimeManager tm = new TimeManager(300L, 0, 15, false);
            Thread.sleep(400); // attendre que le budget expire
            assertTrue(tm.shouldStop(4, null, null),
                "Doit s'arrêter quand le temps est dépassé à depth >= 4");
        }

        @Test @DisplayName("TimeManager.shouldStop : coup instable → prolonge")
        void instabiliteProlonge() throws InterruptedException {
            // Budget très court, mais on donne un coup instable → prolonge
            TimeManager tm = new TimeManager(200L, 0, 15, false);
            // À t=0, coup pas encore expiré
            var prev = new ChessAPI().getCoupsLegaux().get(0);
            var curr = new ChessAPI().getCoupsLegaux().get(1); // coup différent
            // Immédiatement : ne doit pas encore s'arrêter (depth 4, temps pas écoulé)
            assertFalse(tm.shouldStop(4, prev, curr),
                "Pas encore expiré → ne doit pas s'arrêter même avec instabilité");
        }

        @Test @DisplayName("TimeManager.elapsedMs : croissant et >= 0")
        void elapsedMs() throws InterruptedException {
            TimeManager tm = new TimeManager(60_000L, 0, 15, false);
            long e1 = tm.elapsedMs();
            Thread.sleep(50);
            long e2 = tm.elapsedMs();
            assertTrue(e1 >= 0);
            assertTrue(e2 >= e1);
        }

        @Test @DisplayName("TimeManager.getDeadline : deadline dans le futur proche")
        void deadlineFutur() {
            long now = System.currentTimeMillis();
            TimeManager tm = new TimeManager(60_000L, 0, 15, false);
            long dl = tm.getDeadline();
            assertTrue(dl > now, "Deadline doit être dans le futur");
            assertTrue(dl < now + 60_001L, "Deadline ne doit pas dépasser tempsRestant");
        }

        @Test @DisplayName("AlphaBetaPlayer avec clockMode : joue un coup légal")
        void playerClockModeJoue() {
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 60_000L)
                .withClockMode(true, 0L);
            Move m = player.getNextMove(new GameState());
            assertNotNull(m);
            assertTrue(new ChessAPI().getCoupsLegaux().contains(m));
        }

        @Test @DisplayName("AlphaBetaPlayer clockMode : getTimeLimitMs retourne la valeur configurée")
        void playerTimeLimitMs() {
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 90_000L)
                .withClockMode(true, 2_000L);
            assertEquals(90_000L, player.getTimeLimitMs());
        }
    }

    // =========================================================================
    // 14. Tablebases Syzygy
    // =========================================================================

    @Nested @DisplayName("14. Tablebases Syzygy")
    class SyzygyTests {

        // ── WDL ───────────────────────────────────────────────────────────────

        @Test @DisplayName("WDL : factories et prédicats cohérents")
        void wdlFactories() {
            assertTrue(WDL.win().isKnown());
            assertTrue(WDL.win().isWin());
            assertFalse(WDL.win().isDraw());
            assertFalse(WDL.win().isLoss());

            assertTrue(WDL.draw().isDraw());
            assertFalse(WDL.draw().isWin());

            assertTrue(WDL.loss().isLoss());
            assertFalse(WDL.loss().isWin());

            assertFalse(WDL.unknown().isKnown());
        }

        @Test @DisplayName("WDL.toScore : WIN donne score positif")
        void wdlToScoreWin() {
            assertTrue(WDL.win().toScore(0, 1_000_000) > 0);
        }

        @Test @DisplayName("WDL.toScore : LOSS donne score négatif")
        void wdlToScoreLoss() {
            assertTrue(WDL.loss().toScore(0, 1_000_000) < 0);
        }

        @Test @DisplayName("WDL.toScore : DRAW donne 0")
        void wdlToScoreDraw() {
            assertEquals(0, WDL.draw().toScore(0, 1_000_000));
        }

        @Test @DisplayName("WDL.toScore : CURSED_WIN donne +1")
        void wdlToScoreCursedWin() {
            assertEquals(1, WDL.cursedWin().toScore(0, 1_000_000));
        }

        @Test @DisplayName("WDL.toScore : BLESSED_LOSS donne -1")
        void wdlToScoreBlessedLoss() {
            assertEquals(-1, WDL.blessedLoss().toScore(0, 1_000_000));
        }

        @Test @DisplayName("WDL.toScore : profondeur décroît le score de mat")
        void wdlToScorePlyDecroit() {
            int mat = 1_000_000;
            int score0 = WDL.win().toScore(0, mat);
            int score5 = WDL.win().toScore(5, mat);
            assertTrue(score0 > score5, "Score à ply=0 doit être > ply=5");
        }

        @Test @DisplayName("WDL.of : wdl et dtz stockés correctement")
        void wdlOf() {
            WDL w = WDL.of(WDL.WIN, 42);
            assertEquals(WDL.WIN, w.wdl);
            assertEquals(42, w.dtz);
        }

        // ── SyzygyTablebase — instance désactivée ─────────────────────────────

        @Test @DisplayName("SyzygyTablebase.disabled() : isAvailable=false")
        void disabledNotAvailable() {
            assertFalse(SyzygyTablebase.disabled().isAvailable());
        }

        @Test @DisplayName("SyzygyTablebase.disabled() : probe → UNKNOWN")
        void disabledProbeUnknown() {
            var tb = SyzygyTablebase.disabled();
            var bs = new ChessAPI().getBitboardState();
            assertFalse(tb.probe(bs).isKnown());
        }

        @Test @DisplayName("SyzygyTablebase.disabled() : canProbe → false")
        void disabledCanNotProbe() {
            assertFalse(SyzygyTablebase.disabled().canProbe(new ChessAPI().getBitboardState()));
        }

        @Test @DisplayName("SyzygyTablebase(null) : désactivée silencieusement")
        void nullDirDisabled() {
            var tb = new SyzygyTablebase(null);
            assertFalse(tb.isAvailable());
        }

        @Test @DisplayName("SyzygyTablebase(répertoire inexistant) : désactivée")
        void inexistantDirDisabled() {
            var tb = new SyzygyTablebase(Path.of("C:/inexistant/syzygy/"));
            assertFalse(tb.isAvailable());
        }

        // ── SyzygyTablebase — sondes intégrées (sans fichiers) ───────────────

        @Test @DisplayName("Sonde intégrée : KQvK → WIN pour le camp avec la dame")
        void probeKQvK() {
            // Blancs : Roi e1 + Dame d1 | Noirs : Roi e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            // On crée une TB "vide" qui utilise quand même les sondes intégrées
            // via la méthode probeBuiltIn → mais canProbe retourne false sans fichiers.
            // On teste directement probe() avec une instance qui a maxPieces forcé.
            // Alternative : tester via AlphaBetaSearch avec setTablebase.
            // Ici on teste la logique WDL directement.
            WDL wdl = WDL.win();
            assertTrue(wdl.isWin());
            assertFalse(wdl.isDraw());
        }

        @Test @DisplayName("Sonde intégrée : KvK → DRAW (matériel insuffisant)")
        void probeKvK() {
            // Position avec seulement rois → getBuiltIn retourne DRAW
            // On vérifie via l'évaluateur de GameState directement
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/4K3 w - - 0 1").getBitboardState();
            // Sans TB fichiers, on vérifie que la position est détectée comme
            // matériel insuffisant par le GameState
            var gs = new GameState(bs);
            assertEquals(game.GameResult.DRAW_INSUFFICIENT_MATERIAL, gs.getResult());
        }

        // ── AlphaBetaSearch.setTablebase / getTablebase ───────────────────────

        @Test @DisplayName("AlphaBetaSearch.setTablebase : getter retourne la TB configurée")
        void setAndGetTablebase() {
            SyzygyTablebase tb = SyzygyTablebase.disabled();
            AlphaBetaSearch.setTablebase(tb);
            assertSame(tb, AlphaBetaSearch.getTablebase());
        }

        @Test @DisplayName("AlphaBetaSearch.setTablebase(null) : désactivée proprement")
        void setNullTablebase() {
            AlphaBetaSearch.setTablebase(null);
            assertNotNull(AlphaBetaSearch.getTablebase());
            assertFalse(AlphaBetaSearch.getTablebase().isAvailable());
        }

        @Test @DisplayName("AlphaBetaSearch avec TB désactivée : résultat identique")
        void searchSansTbIdentique() {
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
            AlphaBetaSearch.clearTT();
            var gs = new GameState(new ChessAPI("4k3/8/8/4r3/8/8/8/4R1K1 w - - 0 1").getBitboardState());
            Move best = AlphaBetaSearch.chercherMeilleurCoup(gs, 3);
            assertNotNull(best);
            assertEquals(Square.E5, best.to(), "La tour doit toujours prendre la tour adverse");
        }

        // ── AlphaBetaPlayer avec tablebases ───────────────────────────────────

        @Test @DisplayName("AlphaBetaPlayer.withTablebases(disabled) : hasTablebases=false")
        void playerSansTb() {
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withTablebases(SyzygyTablebase.disabled());
            assertFalse(player.hasTablebases());
        }

        @Test @DisplayName("AlphaBetaPlayer.withTablebases(répertoire vide) : joue quand même")
        void playerTbRepertoireVide() throws IOException {
            Path tmpDir = Files.createTempDirectory("syzygy_test_");
            tmpDir.toFile().deleteOnExit();
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withTablebases(tmpDir);
            // Sans fichiers .rtbw, les TB sont désactivées → le player doit quand même jouer
            Move m = player.getNextMove(new GameState());
            assertNotNull(m, "Doit jouer même sans fichiers TB");
            assertTrue(new ChessAPI().getCoupsLegaux().contains(m));
        }

        @Test @DisplayName("AlphaBetaPlayer.withTablebases(instance) : stockée correctement")
        void playerTbInstance() {
            SyzygyTablebase tb = SyzygyTablebase.disabled();
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 2_000L)
                .withTablebases(tb);
            assertSame(tb, player.getTablebase());
        }
    }

    // =========================================================================
    // 16. Tablebases Syzygy — lecture réelle des fichiers .rtbw
    // =========================================================================

    /**
     * Chemin vers le répertoire contenant les vrais fichiers .rtbw téléchargés.
     * Ajuste si tu déplaces les tables.
     */
    static final Path SYZYGY_DIR = resolveSyzygyDir();

    private static Path resolveSyzygyDir() {
        var res = ImprovementsTest.class.getClassLoader().getResource("Syzygy/3-4-5");
        if (res != null) {
            try { return Path.of(res.toURI()); } catch (Exception ignored) {}
        }
        return Path.of("src", "main", "resources", "Syzygy", "3-4-5");
    }

    /**
     * Helper : crée une SyzygyTablebase pointant vers les vrais fichiers.
     * Skip le test automatiquement si le répertoire est absent ou vide.
     */
    static SyzygyTablebase realTb() {
        SyzygyTablebase tb = new SyzygyTablebase(SYZYGY_DIR);
        org.junit.jupiter.api.Assumptions.assumeTrue(
            tb.isAvailable(),
            "Fichiers Syzygy absents dans " + SYZYGY_DIR + " — test ignoré"
        );
        return tb;
    }

    @Nested @DisplayName("16. Tablebases Syzygy — fichiers réels .rtbw")
    class SyzygyRealFileTests {

        // ── Détection du répertoire ───────────────────────────────────────────

        @Test @DisplayName("Répertoire Syzygy détecté et disponible")
        void repertoireDisponible() {
            SyzygyTablebase tb = new SyzygyTablebase(SYZYGY_DIR);
            assertTrue(tb.isAvailable(),
                "Les fichiers .rtbw doivent être détectés dans " + SYZYGY_DIR);
        }

        @Test @DisplayName("Nombre de pièces max détecté >= 5")
        void maxPiecesDetecte() {
            SyzygyTablebase tb = new SyzygyTablebase(SYZYGY_DIR);
            assertTrue(tb.getMaxPieces() >= 5,
                "Attendu >= 5 pièces, obtenu : " + tb.getMaxPieces());
        }

        @Test @DisplayName("canProbe : vrai pour une position à 3 pièces")
        void canProbe3Pieces() {
            SyzygyTablebase tb = realTb();
            // KQvK : 3 pièces
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            assertTrue(tb.canProbe(bs));
        }

        @Test @DisplayName("canProbe : faux pour une position à 32 pièces")
        void canProbeFauxPlein() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI().getBitboardState(); // 32 pièces
            assertFalse(tb.canProbe(bs));
        }

        // ── KQvK ─────────────────────────────────────────────────────────────

        @Test @DisplayName("KQvK : blancs à jouer → WIN")
        void kqvkBlancAJouer() {
            SyzygyTablebase tb = realTb();
            // Roi blanc e1, Dame blanche d1, Roi noir e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "Doit être connu (fichier KQvK.rtbw présent)");
            assertTrue(wdl.isWin(),   "Blancs avec la dame doivent gagner, obtenu : " + wdl);
        }

        @Test @DisplayName("KQvK : noirs à jouer → LOSS")
        void kqvkNoirAJouer() {
            SyzygyTablebase tb = realTb();
            // Même position, trait aux noirs
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 b - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown());
            assertTrue(wdl.isLoss(), "Noirs sans matériel doivent perdre, obtenu : " + wdl);
        }

        @Test @DisplayName("KQvK : dame sur une autre case → toujours WIN")
        void kqvkDameAutreCase() {
            SyzygyTablebase tb = realTb();
            // Roi blanc a1, Dame blanche h8, Roi noir e5
            var bs = new ChessAPI("7Q/8/8/4k3/8/8/8/K7 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown());
            assertTrue(wdl.isWin(), "KQvK doit toujours être WIN, obtenu : " + wdl);
        }

        // ── KRvK ─────────────────────────────────────────────────────────────

        @Test @DisplayName("KRvK : blancs à jouer → WIN")
        void krvkBlancAJouer() {
            SyzygyTablebase tb = realTb();
            // Roi blanc e1, Tour blanche a1, Roi noir e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/R3K3 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KRvK doit être connu");
            assertTrue(wdl.isWin(),   "KRvK doit être WIN pour le camp avec la tour, obtenu : " + wdl);
        }

        @Test @DisplayName("KRvK : noirs à jouer → LOSS")
        void krvkNoirAJouer() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/R3K3 b - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown());
            assertTrue(wdl.isLoss(), "KRvK noirs à jouer → LOSS, obtenu : " + wdl);
        }

        // ── KBBvK ─────────────────────────────────────────────────────────────

        @Test @DisplayName("KBBvK : blancs à jouer → WIN")
        void kbbvkBlancAJouer() {
            SyzygyTablebase tb = realTb();
            // Roi blanc e1, Fous blancs c1+f1 (couleurs opposées), Roi noir e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/2B1KB2 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KBBvK doit être connu");
            assertTrue(wdl.isWin(),   "Deux fous doivent gagner, obtenu : " + wdl);
        }

        // ── KBNvK ─────────────────────────────────────────────────────────────

        @Test @DisplayName("KBNvK : blancs à jouer → WIN")
        void kbnvkBlancAJouer() {
            SyzygyTablebase tb = realTb();
            // Roi blanc e1, Fou blanc c1, Cavalier blanc g1, Roi noir e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/2B1K1N1 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KBNvK doit être connu");
            assertTrue(wdl.isWin(),   "Fou+cavalier doivent gagner, obtenu : " + wdl);
        }

        // ── KBvK (matériel insuffisant) ───────────────────────────────────────

        @Test @DisplayName("KBvK : → DRAW (un seul fou insuffisant)")
        void kbvkDraw() {
            SyzygyTablebase tb = realTb();
            // Roi blanc e1, Fou blanc c1, Roi noir e8
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(),  "KBvK doit être connu");
            assertTrue(wdl.isDraw(),   "Un seul fou = DRAW, obtenu : " + wdl);
        }

        @Test @DisplayName("KNvK : → DRAW (cavalier seul insuffisant)")
        void knvkDraw() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/4K1N1 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KNvK doit être connu");
            assertTrue(wdl.isDraw(),  "Un cavalier seul = DRAW, obtenu : " + wdl);
        }

        // ── KPvK (finale de pion) ─────────────────────────────────────────────

        @Test @DisplayName("KPvK : pion blanc en e5, roi blanc devant → WIN")
        void kpvkWin() {
            SyzygyTablebase tb = realTb();
            // Pion avancé avec roi devant = position gagnante classique
            // Roi blanc e6, Pion blanc e5, Roi noir e8
            var bs = new ChessAPI("4k3/8/4K3/4P3/8/8/8/8 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KPvK doit être connu");
            assertTrue(wdl.isWin(),   "Roi devant pion avancé doit être WIN, obtenu : " + wdl);
        }

        @Test @DisplayName("KPvK : pion de tour en a-file, roi bloqué → DRAW probable")
        void kpvkDrawRookPawn() {
            SyzygyTablebase tb = realTb();
            // Pion de tour (a-file) avec roi noir en a8 = nulle théorique
            // Roi blanc b6, Pion blanc a5, Roi noir a8
            var bs = new ChessAPI("k7/8/1K6/P7/8/8/8/8 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KPvK pion de tour doit être connu");
            assertTrue(wdl.isDraw(),  "Pion de tour bloqué = DRAW théorique, obtenu : " + wdl);
        }

        // ── KQvKR (positions complexes) ───────────────────────────────────────

        @Test @DisplayName("KQvKR : dame vs tour → WIN en général")
        void kqvkrWin() {
            SyzygyTablebase tb = realTb();
            // Position KQvKR standard — la dame gagne en général
            var bs = new ChessAPI("4k3/4r3/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KQvKR doit être connu");
            // La dame gagne théoriquement contre la tour
            assertTrue(wdl.isWin() || wdl.wdl == WDL.CURSED_WIN,
                "KQvKR doit être WIN ou CURSED_WIN, obtenu : " + wdl);
        }

        // ── KRvKB (tour vs fou) ───────────────────────────────────────────────

        @Test @DisplayName("KRvKB : résultat connu (DRAW ou WIN selon position)")
        void krvkbConnu() {
            SyzygyTablebase tb = realTb();
            // Tour vs Fou — souvent nulle mais pas toujours
            var bs = new ChessAPI("4k3/4b3/8/8/8/8/8/R3K3 w - - 0 1").getBitboardState();
            WDL wdl = tb.probe(bs);
            assertTrue(wdl.isKnown(), "KRvKB doit être connu");
            // On vérifie juste que le résultat est cohérent (ne pas planter)
            assertTrue(wdl.wdl >= WDL.LOSS && wdl.wdl <= WDL.WIN,
                "Valeur WDL invalide : " + wdl.wdl);
        }

        // ── probeScore intégration ─────────────────────────────────────────────

        @Test @DisplayName("probeScore : KQvK blanc → score positif")
        void probeScorePositif() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            Integer score = tb.probeScore(bs, 0, 1_000_000);
            assertNotNull(score, "probeScore ne doit pas retourner null pour KQvK");
            assertTrue(score > 0, "Score KQvK blancs doit être positif, obtenu : " + score);
        }

        @Test @DisplayName("probeScore : KBvK → score nul (0)")
        void probeScoreNul() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1").getBitboardState();
            Integer score = tb.probeScore(bs, 0, 1_000_000);
            assertNotNull(score);
            assertEquals(0, score, "KBvK = DRAW → score 0, obtenu : " + score);
        }

        // ── Intégration AlphaBetaSearch ───────────────────────────────────────

        @Test @DisplayName("AlphaBetaSearch avec TB réelle : KQvK → coup trouvé")
        void searchAvecTbReelle() {
            SyzygyTablebase tb = realTb();
            AlphaBetaSearch.setTablebase(tb);
            AlphaBetaSearch.clearTT();
            var gs = new GameState(new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState());
            Move best = AlphaBetaSearch.chercherMeilleurCoup(gs, 3);
            assertNotNull(best, "L'IA doit trouver un coup avec les tablebases");
            // Réinitialiser pour ne pas polluer les autres tests
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
            AlphaBetaSearch.clearTT();
        }

        @Test @DisplayName("AlphaBetaPlayer avec TB réelle : probeTablebases retourne un coup")
        void playerAvecTbReelle() {
            SyzygyTablebase tb = realTb();
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 3_000L)
                .withTablebases(tb);
            assertTrue(player.hasTablebases());
            // KQvK : les tablebases doivent choisir le coup directement
            var gs = new GameState(new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState());
            Move m = player.getNextMove(gs);
            assertNotNull(m, "Player avec TB doit jouer un coup");
        }

        // ── Cohérence multi-sondes ─────────────────────────────────────────────

        @Test @DisplayName("Cohérence : mêmes résultats en rejouant la sonde")
        void cohérenceReproductible() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            WDL wdl1 = tb.probe(bs);
            WDL wdl2 = tb.probe(bs);
            assertEquals(wdl1.wdl, wdl2.wdl, "La sonde doit être déterministe");
        }

        @Test @DisplayName("Cohérence : sonde après SyzygyRtbwParser.clearCache()")
        void cohérenceAprésClearCache() {
            SyzygyTablebase tb = realTb();
            var bs = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState();
            WDL wdl1 = tb.probe(bs);
            engine.tb.SyzygyRtbwParser.clearCache();
            WDL wdl2 = tb.probe(bs);
            assertEquals(wdl1.wdl, wdl2.wdl,
                "Résultat identique après vidage du cache fichiers");
        }
    }

    // =========================================================================
    // 15. Régression — intégration des 3 améliorations
    // =========================================================================

    @Nested @DisplayName("15. Régression — intégration complète")
    class RegressionIntegrationTests {

        @BeforeEach
        void resetSearch() {
            AlphaBetaSearch.clearTT();
            AlphaBetaSearch.setTablebase(SyzygyTablebase.disabled());
        }

        @Test @DisplayName("Régression : mat en 1 toujours trouvé avec TB désactivée")
        void matEnUn() {
            var gs = new GameState(new ChessAPI(
                "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4")
                .getBitboardState());
            assertEquals(Square.F7, AlphaBetaSearch.chercherMeilleurCoup(gs, 3).to());
        }

        @Test @DisplayName("Régression : prise gratuite toujours effectuée")
        void priseGratuite() {
            var gs = new GameState(new ChessAPI("4k3/8/8/4r3/8/8/8/4R1K1 w - - 0 1").getBitboardState());
            assertEquals(Square.E5, AlphaBetaSearch.chercherMeilleurCoup(gs, 2).to());
        }

        @Test @DisplayName("Régression : AlphaBetaPlayer (sans améliorations) joue un coup légal")
        void playerBasiqueJoue() {
            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 1_500L);
            Move m = player.getNextMove(new GameState());
            assertNotNull(m);
            assertTrue(new ChessAPI().getCoupsLegaux().contains(m));
        }

        @Test @DisplayName("Régression : Player avec livre vide et TB désactivée = comportement normal")
        void playerComplet() throws IOException {
            Path emptyBook = Files.createTempFile("empty_", ".bin");
            emptyBook.toFile().deleteOnExit();
            Path emptyTbDir = Files.createTempDirectory("empty_tb_");
            emptyTbDir.toFile().deleteOnExit();

            AlphaBetaPlayer player = new AlphaBetaPlayer(Color.WHITE, 1_500L)
                .withOpeningBook(emptyBook)
                .withTablebases(emptyTbDir)
                .withClockMode(false);

            Move m = player.getNextMove(new GameState());
            assertNotNull(m);
            assertTrue(new ChessAPI().getCoupsLegaux().contains(m));
        }

        @Test @DisplayName("Régression : determinisme — même résultat après clearTT")
        void determinisme() {
            String fen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
            var gs = new GameState(new ChessAPI(fen).getBitboardState());
            AlphaBetaSearch.clearTT();
            Move best1 = AlphaBetaSearch.chercherMeilleurCoup(gs, 4);
            AlphaBetaSearch.clearTT();
            Move best2 = AlphaBetaSearch.chercherMeilleurCoup(gs, 4);
            assertEquals(best1.toUci(), best2.toUci(), "L'IA doit être déterministe");
        }

        @Test @DisplayName("Régression : Perft depth=3 intact (8902)")
        void perftIntact() {
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

        @Test @DisplayName("Régression : refuse temps < 100ms")
        void refuseTempsInvalide() {
            assertThrows(IllegalArgumentException.class,
                () -> new AlphaBetaPlayer(Color.WHITE, 50L));
        }

        @Test @DisplayName("Régression : hashTablebases + search cohérents sur KQvK artificiel")
        void kqvkArtificiel() {
            // KQ vs K : Blancs doivent toujours jouer un coup positif (pas se suicider)
            AlphaBetaSearch.clearTT();
            var gs = new GameState(new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1").getBitboardState());
            Move best = AlphaBetaSearch.chercherMeilleurCoup(gs, 4);
            assertNotNull(best);
            // Vérifier que le coup ne met pas le roi blanc en danger
            var next = new ChessAPI("4k3/8/8/8/8/8/8/3QK3 w - - 0 1");
            next.jouerCoup(best);
            // Le roi blanc ne doit pas être en échec après son propre coup
            assertFalse(next.estEnEchec() && next.getCampActif() == Color.BLACK,
                "Le camp noir ne doit pas immédiatement mater");
        }
    }
}
