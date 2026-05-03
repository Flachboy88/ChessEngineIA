import api.ChessAPI;
import engine.search.AlphaBetaSearch;
import game.GameResult;
import model.Color;
import model.Move;
import player.Player;
import player.classical.AlphaBetaPlayer;
import player.classical.HumanPlayer;
import player.classical.RandomAIPlayer;

import java.util.List;
import java.util.Scanner;

public final class Main {

    public static void main(String[] args) {

        if (args.length >= 1 && args[0].equalsIgnoreCase("-silent")) {
            lancerPartieIAvsIASilencieuse();
            return;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("-bench")) {
            try {
                int n = Integer.parseInt(args[1]);
                lancerBenchmark(n);
            } catch (NumberFormatException e) {
                System.err.println("Usage : java Main -bench <N>");
            }
            return;
        }

        lancerModeInteractif();
    }

    // =========================================================
    // MODE INTERACTIF
    // =========================================================

    private static void lancerModeInteractif() {
        Scanner scanner = new Scanner(System.in);

        ChessAPI api = new ChessAPI();

        Player blanc = new HumanPlayer(Color.WHITE, "Joueur 1");
        Player noir  = new HumanPlayer(Color.BLACK, "Joueur 2");

        api.setWhitePlayer(blanc);
        api.setBlackPlayer(noir);

        jouerPartieBoucle(api, scanner);
        scanner.close();
    }

    private static void jouerPartieBoucle(ChessAPI api, Scanner scanner) {
        int numCoup = 1;

        while (!api.estTerminee()) {

            System.out.println(api.afficherEchiquier());

            if (api.getJoueurActuel() instanceof HumanPlayer) {

                System.out.print("Coup " + numCoup + " > ");
                String saisie = scanner.nextLine();

                try {
                    Move coup = api.jouerCoup(saisie);
                    System.out.println("✓ " + coup.toUci());
                    numCoup++;
                } catch (Exception e) {
                    System.out.println("Coup invalide !");
                }

            } else {
                Move coup = api.jouerCoupJoueur();
                System.out.println("IA joue : " + coup.toUci());
                numCoup++;
            }
        }

        afficherResultat(api.getEtatPartie());
    }

    // =========================================================
    // IA vs IA
    // =========================================================

    private static void lancerPartieIAvsIASilencieuse() {
        ChessAPI api = new ChessAPI();
        api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE));
        api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));

        jouerPartieIAVsIA(api, false);
    }

    public static GameResult jouerPartieIAVsIA(ChessAPI api, boolean verbose) {
        int numCoup = 1;

        while (!api.estTerminee() && numCoup < 500) {

            Move coup = api.jouerCoupJoueur();

            if (verbose) {
                System.out.println("Coup " + numCoup + " : " + coup.toUci());
            }

            numCoup++;
        }

        GameResult result = api.getEtatPartie();

        if (verbose) {
            afficherResultat(result);
        }

        return result;
    }

    // =========================================================
    // BENCHMARK
    // =========================================================

    private static void lancerBenchmark(int nbParties) {
        long timeMs = AlphaBetaPlayer.DEFAULT_TIME_MS;
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  BENCHMARK : %d parties IA vs IA%n", nbParties);
        System.out.printf("  Blancs : AlphaBeta time=%ds%n", timeMs / 1000);
        System.out.println("  Noirs  : Random AI");
        System.out.println("═══════════════════════════════════════");

        int victBlanc = 0, victNoir = 0, nuls = 0;
        long totalMs = 0;

        for (int i = 1; i <= nbParties; i++) {

            // 🔥 IMPORTANT : reset de la transposition table
            AlphaBetaSearch.clearTT();

            ChessAPI api = new ChessAPI();
            api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE, timeMs));
            api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));

            long t0 = System.currentTimeMillis();
            GameResult result = jouerPartieIAVsIA(api, false);
            long ms = System.currentTimeMillis() - t0;
            totalMs += ms;

            switch (result) {
                case WHITE_WINS -> victBlanc++;
                case BLACK_WINS -> victNoir++;
                default -> nuls++;
            }

            System.out.printf("  Partie %3d/%d : %-25s (%dms)%n",
                    i, nbParties, result, ms);
        }

        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Victoires Blancs : %d%n", victBlanc);
        System.out.printf("  Victoires Noirs  : %d%n", victNoir);
        System.out.printf("  Nuls             : %d%n", nuls);
        System.out.printf("  Temps moyen      : %dms%n", totalMs / nbParties);
        System.out.println("═══════════════════════════════════════");
    }

    // =========================================================
    // UTILS
    // =========================================================

    private static void afficherResultat(GameResult result) {
        System.out.println("Résultat : " + result);
    }
}
