# ChessOptiIa

Moteur d'échecs haute performance en Java avec représentation **bitboard**, conçu pour le deep learning.

## Structure du projet

```
ChessOptiIa/
├── src/
│   ├── main/java/
│   │   ├── core/         # Moteur bitboard
│   │   │   ├── Bitboard.java        — Constantes et utilitaires bit
│   │   │   └── BitboardState.java   — État complet (12 BBs + flags)
│   │   ├── model/        # Types de base
│   │   │   ├── Color.java
│   │   │   ├── Piece.java
│   │   │   ├── Square.java
│   │   │   ├── Move.java
│   │   │   └── CastlingRights.java
│   │   ├── rules/        # Génération de coups
│   │   │   ├── AttackTables.java    — Tables précalculées
│   │   │   └── MoveGenerator.java  — Génération légale + application
│   │   ├── game/         # Gestion de partie
│   │   │   ├── GameState.java      — État courant + historique undo
│   │   │   ├── GameResult.java     — Résultats possibles
│   │   │   └── FenParser.java      — Parse/sérialise FEN
│   │   ├── player/       # Joueurs
│   │   │   ├── Player.java         — Interface joueur
│   │   │   ├── HumanPlayer.java    — Joueur humain
│   │   │   ├── AIPlayer.java       — Base IA abstraite
│   │   │   └── RandomAIPlayer.java — IA aléatoire (tests)
│   │   └── api/          # Surcouche haut niveau
│   │       └── ChessAPI.java       — Façade principale
│   └── test/java/
│       └── ChessEngineTest.java    — Tests JUnit 5 complets
├── lib/                  # JARs JUnit 5 (voir lib/README.md)
│   └── README.md
└── ChessOptiIa.iml       # Config IntelliJ

```

## Setup IntelliJ

1. **Ouvrir le projet** : File → Open → dossier ChessOptiIa
2. **Placer les JARs JUnit 5** dans `lib/` (voir `lib/README.md` pour les URLs)
3. **Configurer le SDK** : File → Project Structure → SDK → Java 17+
4. Le fichier `.iml` configure automatiquement les sources et les JARs

## API principale (ChessAPI)

```java
ChessAPI api = new ChessAPI();                    // position initiale
ChessAPI api = new ChessAPI("fen string");        // depuis FEN

api.jouerCoup(Square.E2, Square.E4);              // case → case
api.jouerCoup("e2e4");                            // notation UCI
api.jouerCoup(Square.E7, Square.E8, Piece.QUEEN); // avec promotion

List<Move> coups = api.getCoupsLegaux();
List<Move> coups = api.getCoups(Square.E2);       // depuis une case

GameResult result = api.getEtatPartie();
boolean echec = api.estEnEchec();
String fen = api.toFEN();
api.fromFEN("...");
api.undo();
api.reset();
api.afficherEchiquier();                          // affichage console
```

## Prochaines étapes (Étape 2 — IA)

- Implémenter `AIPlayer.evaluate()` avec une fonction matérielle
- Ajouter Minimax / Alpha-Beta dans une sous-classe
- Intégrer un réseau de neurones (entrée = 12 bitboards = 12×64 bits)

