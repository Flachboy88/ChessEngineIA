import api.ChessAPI;
import game.GameResult;
import model.Color;
import model.Move;
import player.Player;
import player.classical.AlphaBetaPlayer;
import player.classical.HumanPlayer;
import player.classical.RandomAIPlayer;

import java.util.List;
import java.util.Random;
import java.util.Scanner;

/**
 * Point d'entrée principal — partie en console.
 *
 * <h2>Modes disponibles</h2>
 * <ol>
 *   <li>Humain vs Humain</li>
 *   <li>Humain (Blancs) vs IA (Noirs)</li>
 *   <li>IA (Blancs) vs Humain (Noirs)</li>
 *   <li>IA vs IA  (mode silencieux disponible — idéal pour entraînements/benchmarks)</li>
 * </ol>
 *
 * <h2>Options de lancement</h2>
 * <pre>
 *   java Main              → mode interactif (menu console)
 *   java Main -silent      → IA vs IA silencieux (pas d'affichage, résultat final seulement)
 *   java Main -bench N     → N parties IA vs IA en silencieux, affiche statistiques
 * </pre>
 *
 * Saisie des coups en notation UCI : "e2e4", "e1g1" (roque), "e7e8q" (promotion).
 * Commandes spéciales : "aide" → coups légaux | "undo" → annuler | "quit" → quitter.
 */
public final class Main {

    public static void main(String[] args) {

        // ── Parsing des arguments de lancement ──────────────────────────────
        if (args.length >= 1 && args[0].equalsIgnoreCase("-silent")) {
            // Mode IA vs IA silencieux — une seule partie
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

        // ── Mode interactif (défaut) ─────────────────────────────────────────
        lancerModeInteractif();
    }

    // =========================================================================
    // Mode interactif (console)
    // =========================================================================

    private static void lancerModeInteractif() {
        Scanner scanner = new Scanner(System.in);

        afficherBanniere();

        // Choisir le mode
        int mode = choisirMode(scanner);

        // Configurer les joueurs selon le mode
        ChessAPI api = new ChessAPI();
        Player blanc, noir;

        switch (mode) {
            case 1 -> {
                blanc = new HumanPlayer(Color.WHITE, saisirNom(scanner, "Joueur Blanc", "Joueur 1"));
                noir  = new HumanPlayer(Color.BLACK, saisirNom(scanner, "Joueur Noir",  "Joueur 2"));
                System.out.println("\n▶ " + blanc.getName() + " (Blancs) vs " + noir.getName() + " (Noirs)");
            }
            case 2 -> {
                blanc = new HumanPlayer(Color.WHITE, saisirNom(scanner, "Votre nom", "Joueur"));
                noir  = creerIAAvecChoix(scanner, Color.BLACK, "Noirs");
                System.out.println("\n▶ " + blanc.getName() + " (Blancs) vs " + noir.getName() + " (Noirs IA)");
            }
            case 3 -> {
                blanc = creerIAAvecChoix(scanner, Color.WHITE, "Blancs");
                noir  = new HumanPlayer(Color.BLACK, saisirNom(scanner, "Votre nom", "Joueur"));
                System.out.println("\n▶ " + blanc.getName() + " (Blancs IA) vs " + noir.getName() + " (Noirs)");
            }
            case 4 -> {
                System.out.println("\nConfiguration de l'IA Blanche :");
                blanc = creerIAAvecChoix(scanner, Color.WHITE, "Blancs");
                System.out.println("Configuration de l'IA Noire :");
                noir  = creerIAAvecChoix(scanner, Color.BLACK, "Noirs");
                boolean silencieux = demanderOuiNon(scanner, "Mode silencieux (sans affichage du plateau) ?", false);
                System.out.println("\n▶ " + blanc.getName() + " (Blancs IA) vs " + noir.getName() + " (Noirs IA)");
                if (silencieux) {
                    api.setWhitePlayer(blanc);
                    api.setBlackPlayer(noir);
                    jouerPartieIAVsIA(api, false);
                    scanner.close();
                    return;
                }
            }
            default -> {
                blanc = new HumanPlayer(Color.WHITE, "Joueur 1");
                noir  = new HumanPlayer(Color.BLACK, "Joueur 2");
            }
        }

        api.setWhitePlayer(blanc);
        api.setBlackPlayer(noir);

        System.out.println("Commandes : coup UCI (ex: e2e4) | \"aide\" = coups légaux | \"undo\" = annuler | \"quit\" = quitter\n");

        jouerPartieBoucle(api, scanner, mode);
        scanner.close();
    }

    // =========================================================================
    // Boucle de partie interactive
    // =========================================================================

    private static void jouerPartieBoucle(ChessAPI api, Scanner scanner, int mode) {
        int numCoup = 1;

        while (!api.estTerminee()) {
            Color campActif = api.getCampActif();
            boolean campEstHumain = (api.getJoueurActuel() instanceof HumanPlayer);

            // Affichage du plateau
            System.out.println(api.afficherEchiquier());
            if (api.estEnEchec()) {
                System.out.println("  ⚠️  " + nomCamp(campActif) + " est en ÉCHEC !");
            }

            if (campEstHumain) {
                // ── Tour humain ────────────────────────────────────────────────
                String nomJoueur = api.getJoueurActuel().getName();
                System.out.printf("Coup n°%d — %s (%s) > ", numCoup, nomJoueur, nomCamp(campActif));
                String saisie = scanner.nextLine().trim().toLowerCase();

                switch (saisie) {
                    case "quit", "exit" -> { System.out.println("Partie abandonnée."); return; }
                    case "aide", "help" -> { afficherCoupsLegaux(api); continue; }
                    case "undo" -> {
                        if (api.undo()) {
                            System.out.println("  ↩ Coup annulé.");
                            if (numCoup > 1) numCoup--;
                        } else {
                            System.out.println("  Aucun coup à annuler.");
                        }
                        continue;
                    }
                }

                try {
                    Move coup = api.jouerCoup(saisie);
                    System.out.println("  ✓ " + formatCoup(coup));
                    numCoup++;
                } catch (IllegalArgumentException e) {
                    System.out.println("  ✗ Coup invalide (\"" + saisie + "\"). Tapez \"aide\" pour voir les coups.");
                }

            } else {
                // ── Tour IA ────────────────────────────────────────────────────
                System.out.printf("Coup n°%d — %s (%s) réfléchit...",
                    numCoup, api.getJoueurActuel().getName(), nomCamp(campActif));
                Move coup = api.jouerCoupJoueur();
                System.out.println(" joue : " + formatCoup(coup));
                numCoup++;

                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }

        System.out.println();
        System.out.println(api.afficherEchiquier());
        afficherResultat(api.getEtatPartie());
    }

    // =========================================================================
    // Mode IA vs IA silencieux (une partie)
    // =========================================================================

    private static void lancerPartieIAvsIASilencieuse() {
        ChessAPI api = new ChessAPI();
        api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE));
        api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));
        jouerPartieIAVsIA(api, false);
    }

    /**
     * Joue une partie IA vs IA jusqu'à la fin.
     * @param api       état de la partie avec joueurs déjà configurés
     * @param verbose   si true, affiche chaque coup ; si false, seulement le résultat
     * @return          résultat de la partie
     */
    public static GameResult jouerPartieIAVsIA(ChessAPI api, boolean verbose) {
        int numCoup = 1;
        int maxCoups = 500; // sécurité anti-boucle

        while (!api.estTerminee() && numCoup <= maxCoups) {
            if (verbose) {
                System.out.printf("Coup %d (%s) : ", numCoup, nomCamp(api.getCampActif()));
            }
            Move coup = api.jouerCoupJoueur();
            if (verbose) {
                System.out.println(formatCoup(coup));
            }
            numCoup++;
        }

        GameResult result = api.getEtatPartie();
        if (verbose || numCoup > maxCoups) {
            afficherResultat(result);
        } else {
            System.out.println("Résultat : " + result
                + " (" + (numCoup - 1) + " coups)");
        }
        return result;
    }

    // =========================================================================
    // Mode benchmark (N parties IA vs IA)
    // =========================================================================

    private static void lancerBenchmark(int nbParties) {
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  BENCHMARK : %d parties IA vs IA%n", nbParties);
        System.out.println("  Blancs : AlphaBeta depth=4");
        System.out.println("  Noirs  : Random AI");
        System.out.println("═══════════════════════════════════════");

        int victBlanc = 0, victNoir = 0, nuls = 0;
        long totalMs = 0;

        for (int i = 1; i <= nbParties; i++) {
            ChessAPI api = new ChessAPI();
            api.setWhitePlayer(new AlphaBetaPlayer(Color.WHITE));
            api.setBlackPlayer(new RandomAIPlayer(Color.BLACK));

            long t0 = System.currentTimeMillis();
            GameResult result = jouerPartieIAVsIA(api, false);
            long ms = System.currentTimeMillis() - t0;
            totalMs += ms;

            switch (result) {
                case WHITE_WINS -> victBlanc++;
                case BLACK_WINS -> victNoir++;
                default         -> nuls++;
            }

            System.out.printf("  Partie %3d/%d : %-25s (%dms)%n",
                i, nbParties, result, ms);
        }

        System.out.println("═══════════════════════════════════════");
        System.out.printf("  Victoires Blancs (AlphaBeta) : %d/%d (%.1f%%)%n",
            victBlanc, nbParties, 100.0 * victBlanc / nbParties);
        System.out.printf("  Victoires Noirs  (Random)    : %d/%d (%.1f%%)%n",
            victNoir,  nbParties, 100.0 * victNoir  / nbParties);
        System.out.printf("  Nuls                         : %d/%d (%.1f%%)%n",
            nuls,      nbParties, 100.0 * nuls      / nbParties);
        System.out.printf("  Temps moyen par partie       : %dms%n", totalMs / nbParties);
        System.out.println("═══════════════════════════════════════");
    }

    // =========================================================================
    // Saisie console — helpers
    // =========================================================================

    private static void afficherBanniere() {
        System.out.println("╔══════════════════════════════╗");
        System.out.println("║      ChessOptiIa — v1.0      ║");
        System.out.println("╚══════════════════════════════╝");
        System.out.println();
    }

    private static int choisirMode(Scanner scanner) {
        System.out.println("Choisissez le mode de jeu :");
        System.out.println("  1. Humain vs Humain");
        System.out.println("  2. Humain (Blancs) vs IA (Noirs)");
        System.out.println("  3. IA (Blancs) vs Humain (Noirs)");
        System.out.println("  4. IA vs IA");
        System.out.print("Votre choix (1-4) : ");
        while (true) {
            String s = scanner.nextLine().trim();
            if (s.matches("[1-4]")) return Integer.parseInt(s);
            System.out.print("Choix invalide. Entrez 1, 2, 3 ou 4 : ");
        }
    }

    /**
     * Fait choisir le type d'IA et sa profondeur (si applicable) en console.
     */
    private static Player creerIAAvecChoix(Scanner scanner, Color color, String camp) {
        System.out.println("  Type d'IA pour les " + camp + " :");
        System.out.println("    1. AlphaBeta (recommandée)");
        System.out.println("    2. Aléatoire");
        System.out.print("  Choix (1/2) : ");
        String choix;
        while (true) {
            choix = scanner.nextLine().trim();
            if (choix.equals("1") || choix.equals("2")) break;
            System.out.print("  Choix invalide (1 ou 2) : ");
        }

        if (choix.equals("2")) {
            return new RandomAIPlayer(color);
        }

        // AlphaBeta → demander la profondeur
        System.out.print("  Profondeur de recherche (1-8, défaut=4) : ");
        int depth = AlphaBetaPlayer.DEFAULT_DEPTH;
        String depthStr = scanner.nextLine().trim();
        if (!depthStr.isEmpty()) {
            try {
                int d = Integer.parseInt(depthStr);
                if (d >= 1 && d <= 8) depth = d;
                else System.out.println("  Valeur hors limites, profondeur = " + depth + " utilisée.");
            } catch (NumberFormatException e) {
                System.out.println("  Valeur invalide, profondeur = " + depth + " utilisée.");
            }
        }
        return new AlphaBetaPlayer(color, depth);
    }

    private static String saisirNom(Scanner scanner, String prompt, String defaut) {
        System.out.print("  " + prompt + " (défaut: " + defaut + ") : ");
        String s = scanner.nextLine().trim();
        return s.isEmpty() ? defaut : s;
    }

    private static boolean demanderOuiNon(Scanner scanner, String question, boolean defaut) {
        System.out.print("  " + question + " (o/n, défaut=" + (defaut ? "o" : "n") + ") : ");
        String s = scanner.nextLine().trim().toLowerCase();
        if (s.isEmpty()) return defaut;
        return s.startsWith("o") || s.startsWith("y");
    }

    // =========================================================================
    // Affichage
    // =========================================================================

    private static String nomCamp(Color camp) {
        return camp == Color.WHITE ? "Blancs" : "Noirs";
    }

    private static String formatCoup(Move coup) {
        String uci = coup.toUci();
        if (coup.isCastling())   return uci + " (roque)";
        if (coup.isEnPassant())  return uci + " (en passant)";
        if (coup.isPromotion())  return uci + " (promotion)";
        return uci;
    }

    private static void afficherCoupsLegaux(ChessAPI api) {
        List<Move> coups = api.getCoupsLegaux();
        System.out.println("  Coups légaux (" + coups.size() + ") :");
        coups.stream()
             .collect(java.util.stream.Collectors.groupingBy(m -> m.from().toAlgebraic()))
             .entrySet().stream()
             .sorted(java.util.Map.Entry.comparingByKey())
             .forEach(e -> {
                 String dests = e.getValue().stream()
                     .map(m -> m.to().toAlgebraic()
                         + (m.isCastling() ? "(O)" : m.isPromotion() ? "(=)" : ""))
                     .collect(java.util.stream.Collectors.joining(" "));
                 System.out.println("    " + e.getKey() + " → " + dests);
             });
    }

    private static void afficherResultat(GameResult result) {
        System.out.println("══════════════════════════════");
        System.out.println("  RÉSULTAT : " + switch (result) {
            case WHITE_WINS                  -> "Les Blancs gagnent ! (Échec et Mat)";
            case BLACK_WINS                  -> "Les Noirs gagnent ! (Échec et Mat)";
            case STALEMATE                   -> "Pat — Match nul !";
            case DRAW_50_MOVES               -> "Nul — Règle des 50 coups.";
            case DRAW_INSUFFICIENT_MATERIAL  -> "Nul — Matériel insuffisant.";
            default                          -> result.toString();
        });
        System.out.println("══════════════════════════════");
    }
}
