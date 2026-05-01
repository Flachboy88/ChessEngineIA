import api.ChessAPI;
import game.GameResult;
import model.Color;
import model.Move;
import player.HumanPlayer;
import player.RandomAIPlayer;

import java.util.List;
import java.util.Scanner;

/**
 * Point d'entrée principal — partie en console.
 *
 * Modes disponibles :
 *   1. Humain vs Humain
 *   2. Humain (Blancs) vs IA Aléatoire (Noirs)
 *   3. IA Aléatoire (Blancs) vs Humain (Noirs)
 *
 * Saisie des coups en notation UCI : "e2e4", "e1g1" (roque), "e7e8q" (promotion).
 * Commandes spéciales : "aide" pour voir les coups légaux, "quit" pour quitter.
 */
public final class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔══════════════════════════════╗");
        System.out.println("║      ChessOptiIa — v1.0      ║");
        System.out.println("╚══════════════════════════════╝");
        System.out.println();

        int mode = choisirMode(scanner);
        ChessAPI api = new ChessAPI();

        HumanPlayer humanWhite = null;
        HumanPlayer humanBlack = null;
        RandomAIPlayer aiWhite = null;
        RandomAIPlayer aiBlack = null;

        switch (mode) {
            case 1 -> {
                humanWhite = new HumanPlayer(Color.WHITE, "Joueur 1");
                humanBlack = new HumanPlayer(Color.BLACK, "Joueur 2");
                api.setWhitePlayer(humanWhite);
                api.setBlackPlayer(humanBlack);
                System.out.println("\n▶ Humain (Blancs) vs Humain (Noirs)");
            }
            case 2 -> {
                humanWhite = new HumanPlayer(Color.WHITE, "Joueur");
                aiBlack    = new RandomAIPlayer(Color.BLACK, "IA Aléatoire", new java.util.Random());
                api.setWhitePlayer(humanWhite);
                api.setBlackPlayer(aiBlack);
                System.out.println("\n▶ Humain (Blancs) vs IA Aléatoire (Noirs)");
            }
            case 3 -> {
                aiWhite    = new RandomAIPlayer(Color.WHITE, "IA Aléatoire", new java.util.Random());
                humanBlack = new HumanPlayer(Color.BLACK, "Joueur");
                api.setWhitePlayer(aiWhite);
                api.setBlackPlayer(humanBlack);
                System.out.println("\n▶ IA Aléatoire (Blancs) vs Humain (Noirs)");
            }
        }

        System.out.println("Commandes : saisissez un coup UCI (ex: e2e4) | \"aide\" = coups légaux | \"quit\" = quitter");
        System.out.println();

        int numCoup = 1;

        // ── Boucle de jeu ─────────────────────────────────────────────────────
        while (!api.estTerminee()) {
            Color campActif = api.getCampActif();

            // Affichage de l'échiquier et de l'état
            System.out.println(api.afficherEchiquier());
            if (api.estEnEchec()) {
                System.out.println("  ⚠️  " + nomCamp(campActif) + " est en ÉCHEC !");
            }

            boolean campEstHumain = isHuman(campActif, mode);

            if (campEstHumain) {
                // ── Tour d'un humain ──────────────────────────────────────────
                HumanPlayer humain = (campActif == Color.WHITE) ? humanWhite : humanBlack;
                String nomJoueur   = (humain != null) ? humain.getName() : "Joueur";

                System.out.printf("Coup n°%d — %s (%s) > ", numCoup, nomJoueur, nomCamp(campActif));

                String saisie = scanner.nextLine().trim().toLowerCase();

                if (saisie.equals("quit")) {
                    System.out.println("Partie abandonnée.");
                    break;
                }

                if (saisie.equals("aide") || saisie.equals("help")) {
                    afficherCoupsLegaux(api);
                    continue; // on ne compte pas ce tour
                }

                if (saisie.equals("undo")) {
                    if (api.undo()) {
                        System.out.println("  ↩ Coup annulé.");
                        if (numCoup > 1) numCoup--;
                    } else {
                        System.out.println("  Aucun coup à annuler.");
                    }
                    continue;
                }

                // Validation et exécution du coup
                try {
                    Move coup = api.jouerCoup(saisie);
                    System.out.println("  ✓ Coup joué : " + formatCoup(coup));
                    numCoup++;
                } catch (IllegalArgumentException e) {
                    System.out.println("  ✗ Coup invalide (\"" + saisie + "\"). Réessayez ou tapez \"aide\".");
                }

            } else {
                // ── Tour de l'IA ──────────────────────────────────────────────
                System.out.printf("Coup n°%d — IA (%s) réfléchit...", numCoup, nomCamp(campActif));
                Move coup = api.jouerCoupJoueur();
                System.out.println(" joue : " + formatCoup(coup));
                numCoup++;

                // Petite pause pour que ce soit lisible
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            }
        }

        // ── Résultat final ────────────────────────────────────────────────────
        System.out.println();
        System.out.println(api.afficherEchiquier());
        afficherResultat(api.getEtatPartie());
        scanner.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int choisirMode(Scanner scanner) {
        System.out.println("Choisissez le mode de jeu :");
        System.out.println("  1. Humain vs Humain");
        System.out.println("  2. Humain (Blancs) vs IA Aléatoire (Noirs)");
        System.out.println("  3. IA Aléatoire (Blancs) vs Humain (Noirs)");
        System.out.print("Votre choix (1/2/3) : ");

        while (true) {
            String s = scanner.nextLine().trim();
            switch (s) {
                case "1" -> { return 1; }
                case "2" -> { return 2; }
                case "3" -> { return 3; }
                default  -> System.out.print("Choix invalide. Entrez 1, 2 ou 3 : ");
            }
        }
    }

    private static boolean isHuman(Color camp, int mode) {
        return switch (mode) {
            case 1 -> true;                          // les deux humains
            case 2 -> camp == Color.WHITE;           // humain = blancs
            case 3 -> camp == Color.BLACK;           // humain = noirs
            default -> true;
        };
    }

    private static String nomCamp(Color camp) {
        return camp == Color.WHITE ? "Blancs" : "Noirs";
    }

    private static String formatCoup(Move coup) {
        String uci = coup.toUci();
        if (coup.isCastling()) {
            return uci + " (roque)";
        } else if (coup.isEnPassant()) {
            return uci + " (en passant)";
        } else if (coup.isPromotion()) {
            return uci + " (promotion)";
        }
        return uci;
    }

    private static void afficherCoupsLegaux(ChessAPI api) {
        List<Move> coups = api.getCoupsLegaux();
        System.out.println("  Coups légaux (" + coups.size() + ") :");

        // Grouper par pièce de départ pour plus de lisibilité
        coups.stream()
             .collect(java.util.stream.Collectors.groupingBy(m -> m.from().toAlgebraic()))
             .entrySet().stream()
             .sorted(java.util.Map.Entry.comparingByKey())
             .forEach(e -> {
                 String dests = e.getValue().stream()
                     .map(m -> m.to().toAlgebraic() + (m.isCastling() ? "(O)" : m.isPromotion() ? "(=)" : ""))
                     .collect(java.util.stream.Collectors.joining(" "));
                 System.out.println("    " + e.getKey() + " → " + dests);
             });
    }

    private static void afficherResultat(GameResult result) {
        System.out.println("══════════════════════════════");
        System.out.println("  RÉSULTAT : " + switch (result) {
            case WHITE_WINS          -> "Les Blancs gagnent ! (Échec et Mat)";
            case BLACK_WINS          -> "Les Noirs gagnent ! (Échec et Mat)";
            case STALEMATE           -> "Pat — Match nul !";
            case DRAW_50_MOVES       -> "Nul — Règle des 50 coups.";
            case DRAW_INSUFFICIENT_MATERIAL -> "Nul — Matériel insuffisant.";
            default                  -> result.toString();
        });
        System.out.println("══════════════════════════════");
    }
}
