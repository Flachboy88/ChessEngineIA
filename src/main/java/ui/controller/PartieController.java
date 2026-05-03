package ui.controller;

import core.BitboardState;
import game.GameResult;
import game.GameState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import model.Move;
import model.Piece;
import model.Square;
import player.classical.HumanPlayer;
import player.Player;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Controller de la vue Partie.
 * Gère le rendu du plateau, l'interaction humain, le jeu IA et l'historique.
 */
public class PartieController {

    // ── FXML ─────────────────────────────────────────────────────────────────
    @FXML private Canvas    boardCanvas;
    @FXML private HBox      coordTop;
    @FXML private HBox      coordBot;
    @FXML private VBox      coordLeft;
    @FXML private VBox      coordRight;
    @FXML private Label     turnLabel;
    @FXML private Label     turnSubLabel;
    @FXML private Region    turnIndicator;
    @FXML private HBox      echecBox;
    @FXML private Label     lastMoveLabel;
    @FXML private GridPane  historiqueGrid;
    @FXML private ScrollPane historiqueScroll;
    @FXML private VBox      inputBox;
    @FXML private TextField inputField;
    @FXML private Label     inputError;
    @FXML private VBox      resultBox;
    @FXML private Label     resultIcon;
    @FXML private Label     resultLabel;
    @FXML private Label     resultSubLabel;
    @FXML private Button    btnUndo;

    // ── État ──────────────────────────────────────────────────────────────────
    private GameState gameState;
    private Player    joueurBlanc;
    private Player    joueurNoir;

    /** Case sélectionnée (clic humain) */
    private Square caseSelectionnee = null;
    /** Liste des coups légaux depuis la case sélectionnée */
    private List<Move> coupsDepuiSelection = List.of();
    /** Dernier coup joué (highlight) */
    private Move dernierCoup = null;

    /**
     * Plateau retourné : si true, les noirs sont en bas.
     * Affecte le rendu et l'interprétation des clics.
     */
    private boolean plateauRetourne = false;

    /**
     * Nombre de coups dans l'historique visuel.
     * Doit correspondre exactement à gameState.getHistorySize().
     */
    private int histoMoveCount = 0;

    // ── Constantes plateau ────────────────────────────────────────────────────
    private static final double CELL  = 70.0;
    private static final double BOARD = CELL * 8;

    // ── Palette chess.com style ───────────────────────────────────────────────
    // Cases de base : bois classique
    private static final Color C_LIGHT      = Color.web("#f0d9b5");
    private static final Color C_DARK       = Color.web("#b58863");

    // Highlight dernier coup : jaune-vert distinctif (comme chess.com)
    private static final Color C_LAST_LIGHT = Color.web("#cdd26a");   // case claire du dernier coup
    private static final Color C_LAST_DARK  = Color.web("#aaa23a");   // case sombre du dernier coup

    // Sélection : bleu-vert clair (case de départ sélectionnée)
    private static final Color C_SELECT_LIGHT = Color.web("#7fc97f", 0.85);
    private static final Color C_SELECT_DARK  = Color.web("#5fa85f", 0.85);

    // Points des coups légaux
    private static final Color C_LEGAL_DOT  = Color.web("#3d3318", 0.38);
    private static final Color C_LEGAL_RING = Color.web("#3d3318", 0.28);

    // Échec : rouge sur la case du roi
    private static final Color C_CHECK_LIGHT = Color.web("#e05050", 0.55);
    private static final Color C_CHECK_DARK  = Color.web("#c03030", 0.65);

    // Symboles Unicode pièces
    private static final String[][] PIECE_UNICODE = {
        { "♙", "♘", "♗", "♖", "♕", "♔" }, // WHITE: P N B R Q K
        { "♟", "♞", "♝", "♜", "♛", "♚" }  // BLACK: p n b r q k
    };

    // ── Exécuteur IA ─────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ia-thread");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────────────────

    public void initialiser(GameState gameState, Player blanc, Player noir) {
        this.gameState   = gameState;
        this.joueurBlanc = blanc;
        this.joueurNoir  = noir;

        construireCoordonnees();
        mettreAJourUI();
        jouerTourSiIA();
    }

    @FXML
    public void initialize() {
        boardCanvas.setOnMouseClicked(e -> {
            int col = (int)(e.getX() / CELL);
            int row = (int)(e.getY() / CELL);
            if (col < 0 || col > 7 || row < 0 || row > 7) return;

            // Adapter les coordonnées selon l'orientation du plateau
            int rang = plateauRetourne ? row : (7 - row);
            int file = plateauRetourne ? (7 - col) : col;
            onClicCase(file, rang);
        });
    }

    // ── Callbacks FXML ───────────────────────────────────────────────────────

    @FXML
    private void onJouerCoup() {
        cacherInputError();
        String uci = inputField.getText().trim().toLowerCase();
        if (uci.isEmpty()) return;

        try {
            Move move = Move.fromUci(uci);
            if (!estCoupLegal(move)) {
                afficherInputError("Coup illégal : " + uci);
                return;
            }
            // Gérer la promotion si besoin (saisie texte sans pièce → demander)
            if (move.isPromotion() && move.promotionPiece() == null) {
                Piece choix = demanderPromotion(gameState.getSideToMove());
                if (choix == null) return;
                move = Move.promotion(move.from(), move.to(), choix);
                if (!estCoupLegal(move)) {
                    afficherInputError("Coup de promotion illégal.");
                    return;
                }
            }
            jouerCoup(move);
            inputField.clear();
        } catch (IllegalArgumentException ex) {
            afficherInputError("Notation invalide (ex: e2e4)");
        }
    }

    @FXML
    private void onUndo() {
        if (gameState.getHistorySize() == 0) return;

        // Retirer le dernier coup affiché dans l'historique
        retirerDernierCoupHistorique();

        gameState.undo();
        dernierCoup = null;
        caseSelectionnee = null;
        coupsDepuiSelection = List.of();
        mettreAJourUI();
    }

    @FXML
    private void onNouvellePartie() {
        gameState.reset();
        dernierCoup = null;
        histoMoveCount = 0;
        caseSelectionnee = null;
        coupsDepuiSelection = List.of();
        historiqueGrid.getChildren().clear();
        historiqueGrid.getRowConstraints().clear();
        mettreAJourUI();
        jouerTourSiIA();
    }

    @FXML
    private void onRetournerPlateau() {
        plateauRetourne = !plateauRetourne;
        construireCoordonnees();
        dessinerPlateau();
    }

    @FXML
    private void onRetourAccueil() {
        executor.shutdownNow();
        try {
            FXMLLoader loader = new FXMLLoader(
                Objects.requireNonNull(getClass().getResource("/ui/view/accueil.fxml")));
            Parent root = loader.load();
            Stage stage = (Stage) boardCanvas.getScene().getWindow();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/ui/view/chess.css")).toExternalForm()
            );
            stage.setScene(scene);
            stage.setTitle("ChessOptiIA — Accueil");
            stage.sizeToScene();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Logique principale ────────────────────────────────────────────────────

    private void onClicCase(int file, int rang) {
        if (gameState.getResult().isOver()) return;

        Player joueurActuel = joueurActuel();
        if (!(joueurActuel instanceof HumanPlayer)) return;

        Square caseClic = Square.fromAlgebraic(colLettre(file) + (rang + 1));

        // Cas 1 : une case est déjà sélectionnée → tenter le coup
        if (caseSelectionnee != null) {
            Move coup = trouverCoupVers(caseClic);
            if (coup != null) {
                // Vérifier si promotion et demander la pièce si humain
                if (coup.isPromotion()) {
                    Piece choix = demanderPromotion(gameState.getSideToMove());
                    if (choix == null) return; // annulé
                    // Trouver le coup de promotion avec la pièce choisie
                    Move coupPromo = coupsDepuiSelection.stream()
                        .filter(m -> m.to().equals(caseClic)
                                  && m.isPromotion()
                                  && m.promotionPiece() == choix)
                        .findFirst().orElse(null);
                    if (coupPromo == null) return;
                    coup = coupPromo;
                }
                jouerCoup(coup);
                return;
            }
        }

        // Cas 2 : sélectionner cette case si elle contient une pièce du joueur courant
        BitboardState bs = gameState.getBitboardState();
        boolean aPiece = aPieceDuJoueur(bs, caseClic, gameState.getSideToMove());
        if (aPiece) {
            caseSelectionnee = caseClic;
            coupsDepuiSelection = gameState.getLegalMoves().stream()
                .filter(m -> m.from().equals(caseSelectionnee))
                .toList();
        } else {
            caseSelectionnee = null;
            coupsDepuiSelection = List.of();
        }
        dessinerPlateau();
    }

    private void jouerCoup(Move coup) {
        histoMoveCount++;
        enregistrerHistorique(coup, histoMoveCount);
        gameState.applyMove(coup);
        dernierCoup = coup;
        caseSelectionnee = null;
        coupsDepuiSelection = List.of();

        mettreAJourUI();

        if (!gameState.getResult().isOver()) {
            jouerTourSiIA();
        }
    }

    private void jouerTourSiIA() {
        Player joueur = joueurActuel();
        if (joueur instanceof HumanPlayer) return;
        if (gameState.getResult().isOver()) return;

        Timeline delay = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            executor.submit(() -> {
                Move coup = joueur.getNextMove(gameState);
                Platform.runLater(() -> jouerCoup(coup));
            });
        }));
        delay.play();
    }

    // ── Dialog promotion ──────────────────────────────────────────────────────

    /**
     * Affiche une fenêtre de choix de promotion pour le joueur humain.
     * @param side camp qui promeut
     * @return la pièce choisie, ou null si annulé
     */
    private Piece demanderPromotion(model.Color side) {
        // Pièces proposées dans l'ordre classique
        Piece[] choix = { Piece.QUEEN, Piece.ROOK, Piece.BISHOP, Piece.KNIGHT };
        String[] symboles = side == model.Color.WHITE
            ? new String[]{"♕", "♖", "♗", "♘"}
            : new String[]{"♛", "♜", "♝", "♞"};
        String[] noms = { "Dame", "Tour", "Fou", "Cavalier" };

        // Boîte de dialogue modale
        Stage dialog = new Stage(StageStyle.UTILITY);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(boardCanvas.getScene().getWindow());
        dialog.setTitle("Promotion du pion");
        dialog.setResizable(false);

        final Piece[] resultat = { Piece.QUEEN }; // défaut au cas où
        final boolean[] choixFait = { false };

        HBox boutons = new HBox(12);
        boutons.setAlignment(Pos.CENTER);
        boutons.setStyle("-fx-background-color: #1e1a10; -fx-padding: 20;");

        for (int i = 0; i < choix.length; i++) {
            final Piece piece = choix[i];
            VBox cellule = new VBox(4);
            cellule.setAlignment(Pos.CENTER);
            cellule.setStyle(
                "-fx-background-color: #2a2418;" +
                "-fx-border-color: #7a6428;" +
                "-fx-border-width: 1px;" +
                "-fx-border-radius: 4px;" +
                "-fx-padding: 10 14 10 14;" +
                "-fx-cursor: hand;"
            );

            Label symLabel = new Label(symboles[i]);
            symLabel.setStyle("-fx-font-size: 36px; -fx-text-fill: " +
                (side == model.Color.WHITE ? "#f8f4e8" : "#1a1408") + ";");

            Label nomLabel = new Label(noms[i]);
            nomLabel.setStyle("-fx-text-fill: #a89878; -fx-font-family: 'Georgia'; -fx-font-size: 11px;");

            cellule.getChildren().addAll(symLabel, nomLabel);

            cellule.setOnMouseEntered(ev ->
                cellule.setStyle(
                    "-fx-background-color: #3d3318;" +
                    "-fx-border-color: #c9a84c;" +
                    "-fx-border-width: 1.5px;" +
                    "-fx-border-radius: 4px;" +
                    "-fx-padding: 10 14 10 14;" +
                    "-fx-cursor: hand;"
                )
            );
            cellule.setOnMouseExited(ev ->
                cellule.setStyle(
                    "-fx-background-color: #2a2418;" +
                    "-fx-border-color: #7a6428;" +
                    "-fx-border-width: 1px;" +
                    "-fx-border-radius: 4px;" +
                    "-fx-padding: 10 14 10 14;" +
                    "-fx-cursor: hand;"
                )
            );
            cellule.setOnMouseClicked(ev -> {
                resultat[0] = piece;
                choixFait[0] = true;
                dialog.close();
            });

            boutons.getChildren().add(cellule);
        }

        Label titre = new Label("Choisir la pièce de promotion");
        titre.setStyle("-fx-text-fill: #c9a84c; -fx-font-family: 'Georgia'; -fx-font-size: 13px;" +
                       "-fx-padding: 16 0 8 0;");

        VBox root = new VBox(8, titre, boutons);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #1e1a10;");

        dialog.setScene(new Scene(root));
        dialog.showAndWait();

        return choixFait[0] ? resultat[0] : null;
    }

    // ── Rendu plateau ─────────────────────────────────────────────────────────

    private void dessinerPlateau() {
        GraphicsContext gc = boardCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BOARD, BOARD);

        BitboardState bs = gameState.getBitboardState();

        Square roiSq = gameState.isCheck()
            ? trouverRoi(bs, gameState.getSideToMove()) : null;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                double x = col * CELL;
                double y = row * CELL;

                // Conversion pixel → coordonnées plateau selon l'orientation
                int file = plateauRetourne ? (7 - col) : col;
                int rang  = plateauRetourne ? row       : (7 - row);

                Square sq = Square.fromAlgebraic(colLettre(file) + (rang + 1));
                boolean caseClaire = ((file + rang) % 2 != 0);

                // ── Couleur de base ──────────────────────────────────────────
                Color bg = caseClaire ? C_LIGHT : C_DARK;

                // Highlight dernier coup (chess.com : jaune-vert)
                boolean estDernierCoup = (dernierCoup != null)
                    && (sq.equals(dernierCoup.from()) || sq.equals(dernierCoup.to()));
                if (estDernierCoup) {
                    bg = caseClaire ? C_LAST_LIGHT : C_LAST_DARK;
                }

                // Highlight sélection (chess.com : vert)
                boolean estSelectionnee = sq.equals(caseSelectionnee);
                if (estSelectionnee) {
                    bg = caseClaire ? C_SELECT_LIGHT : C_SELECT_DARK;
                }

                // Highlight roi en échec (rouge)
                if (roiSq != null && sq.equals(roiSq)) {
                    bg = caseClaire ? C_CHECK_LIGHT : C_CHECK_DARK;
                }

                gc.setFill(bg);
                gc.fillRect(x, y, CELL, CELL);

                // ── Indicateurs des coups légaux ─────────────────────────────
                if (caseSelectionnee != null) {
                    boolean estCibleLegale = coupsDepuiSelection.stream()
                        .anyMatch(m -> m.to().equals(sq));
                    if (estCibleLegale) {
                        boolean aPieceCible = aPieceSurCase(bs, sq);
                        if (aPieceCible) {
                            // Anneau pour les captures
                            gc.setStroke(C_LEGAL_RING);
                            gc.setLineWidth(5);
                            gc.strokeOval(x + 3, y + 3, CELL - 6, CELL - 6);
                        } else {
                            // Point central pour les déplacements
                            gc.setFill(C_LEGAL_DOT);
                            double r = CELL * 0.16;
                            gc.fillOval(x + CELL / 2 - r, y + CELL / 2 - r, r * 2, r * 2);
                        }
                    }
                }

                // ── Dessin de la pièce ───────────────────────────────────────
                dessinePiece(gc, bs, sq, x, y);
            }
        }
    }

    private void dessinePiece(GraphicsContext gc, BitboardState bs, Square sq,
                               double x, double y) {
        for (model.Color c : model.Color.values()) {
            for (Piece p : Piece.values()) {
                long bb = bs.getBitboard(c, p);
                if ((bb & (1L << sq.index)) != 0) {
                    int ci = c.ordinal();
                    String sym = PIECE_UNICODE[ci][p.index];

                    gc.setFont(Font.font("Segoe UI Symbol", CELL * 0.72));
                    gc.setFill(Color.color(0, 0, 0, 0.28));
                    gc.fillText(sym, x + CELL * 0.13 + 1, y + CELL * 0.82 + 1);
                    gc.setFill(ci == 0 ? Color.web("#f8f4e8") : Color.web("#1a1408"));
                    gc.fillText(sym, x + CELL * 0.13, y + CELL * 0.82);
                    return;
                }
            }
        }
    }

    // ── Mise à jour UI ────────────────────────────────────────────────────────

    private void mettreAJourUI() {
        dessinerPlateau();
        mettreAJourTour();
        mettreAJourResultat();
        mettreAJourInputBox();
        if (dernierCoup != null) {
            lastMoveLabel.setText(dernierCoup.toUci().toUpperCase());
        } else {
            lastMoveLabel.setText("—");
        }
    }

    private void mettreAJourTour() {
        model.Color side = gameState.getSideToMove();
        Player joueur = (side == model.Color.WHITE) ? joueurBlanc : joueurNoir;
        boolean estBlanc = (side == model.Color.WHITE);

        turnLabel.setText("Tour des " + (estBlanc ? "Blancs" : "Noirs"));
        turnSubLabel.setText(joueur.getName()
            + (joueur instanceof HumanPlayer ? "" : " (IA)"));

        turnIndicator.getStyleClass().removeAll("turn-indicator-white", "turn-indicator-black");
        turnIndicator.getStyleClass().add(estBlanc ? "turn-indicator-white" : "turn-indicator-black");

        boolean check = gameState.isCheck() && !gameState.getResult().isOver();
        echecBox.setVisible(check);
        echecBox.setManaged(check);
    }

    private void mettreAJourResultat() {
        GameResult result = gameState.getResult();
        boolean over = result.isOver();
        resultBox.setVisible(over);
        resultBox.setManaged(over);

        if (over) {
            switch (result) {
                case WHITE_WINS -> {
                    resultIcon.setText("♔");
                    resultLabel.setText("LES BLANCS GAGNENT");
                    resultSubLabel.setText("Échec et mat");
                }
                case BLACK_WINS -> {
                    resultIcon.setText("♚");
                    resultLabel.setText("LES NOIRS GAGNENT");
                    resultSubLabel.setText("Échec et mat");
                }
                case STALEMATE -> {
                    resultIcon.setText("🤝");
                    resultLabel.setText("NULLE — PAT");
                    resultSubLabel.setText("Aucun coup légal disponible");
                }
                case DRAW_REPETITION -> {
                    resultIcon.setText("♻");
                    resultLabel.setText("NULLE — RÉPÉTITION");
                    resultSubLabel.setText("La position s'est répétée 3 fois");
                }
                default -> {
                    resultIcon.setText("½");
                    resultLabel.setText("NULLE");
                    resultSubLabel.setText(result.description);
                }
            }
        }
    }

    private void mettreAJourInputBox() {
        Player joueur = joueurActuel();
        boolean humainEtPartieEnCours = (joueur instanceof HumanPlayer)
            && !gameState.getResult().isOver();
        inputBox.setVisible(humainEtPartieEnCours);
        inputBox.setManaged(humainEtPartieEnCours);

        btnUndo.setDisable(gameState.getHistorySize() == 0
            || gameState.getResult().isOver());
    }

    // ── Historique ────────────────────────────────────────────────────────────

    private void enregistrerHistorique(Move coup, int moveCount) {
        boolean estBlanc = (gameState.getSideToMove() == model.Color.WHITE);
        int paire = (moveCount - 1) / 2;

        if (estBlanc) {
            Label num = new Label((paire + 1) + ".");
            num.getStyleClass().add("move-num");
            historiqueGrid.add(num, 0, paire);
        }

        Label cell = new Label(coup.toUci().toUpperCase());
        cell.getStyleClass().add("move-cell");

        // Retirer le highlight du dernier coup précédent
        historiqueGrid.getChildren().forEach(n -> {
            if (n instanceof Label l && l.getStyleClass().contains("move-cell-last")) {
                l.getStyleClass().remove("move-cell-last");
            }
        });
        cell.getStyleClass().add("move-cell-last");

        historiqueGrid.add(cell, estBlanc ? 1 : 2, paire);

        Platform.runLater(() -> historiqueScroll.setVvalue(1.0));
    }

    /**
     * Retire le dernier coup de l'historique visuel lors d'un undo.
     * Supprime aussi la ligne de numéro si c'était un coup blanc (début de paire).
     */
    private void retirerDernierCoupHistorique() {
        if (histoMoveCount == 0) return;

        boolean estBlanc = (histoMoveCount % 2 == 1); // coup blanc = numéro impair
        int paire = (histoMoveCount - 1) / 2;
        int colCoup = estBlanc ? 1 : 2;

        // Supprimer la cellule du coup
        historiqueGrid.getChildren().removeIf(node ->
            GridPane.getRowIndex(node) != null
            && GridPane.getRowIndex(node) == paire
            && GridPane.getColumnIndex(node) != null
            && GridPane.getColumnIndex(node) == colCoup
        );

        // Si c'était un coup blanc (colonne 1), supprimer aussi le numéro
        if (estBlanc) {
            historiqueGrid.getChildren().removeIf(node ->
                GridPane.getRowIndex(node) != null
                && GridPane.getRowIndex(node) == paire
                && GridPane.getColumnIndex(node) != null
                && GridPane.getColumnIndex(node) == 0
            );
            if (!historiqueGrid.getRowConstraints().isEmpty()) {
                historiqueGrid.getRowConstraints().remove(
                    historiqueGrid.getRowConstraints().size() - 1);
            }
        }

        histoMoveCount--;

        // Re-marquer le nouveau dernier coup comme actif
        if (histoMoveCount > 0) {
            int nouvPaire = (histoMoveCount - 1) / 2;
            int nouvCol = (histoMoveCount % 2 == 1) ? 1 : 2;
            historiqueGrid.getChildren().stream()
                .filter(n -> n instanceof Label
                          && GridPane.getRowIndex(n) != null
                          && GridPane.getRowIndex(n) == nouvPaire
                          && GridPane.getColumnIndex(n) != null
                          && GridPane.getColumnIndex(n) == nouvCol)
                .forEach(n -> ((Label) n).getStyleClass().add("move-cell-last"));
        }
    }

    // ── Coordonnées plateau ───────────────────────────────────────────────────

    private void construireCoordonnees() {
        String styleCoord = "coord-label";

        coordTop.getChildren().clear();
        coordBot.getChildren().clear();
        Region spacerL  = new Region(); spacerL.setPrefWidth(20);
        Region spacerL2 = new Region(); spacerL2.setPrefWidth(20);
        coordTop.getChildren().add(spacerL);
        coordBot.getChildren().add(spacerL2);

        // Colonnes : a-h ou h-a selon l'orientation
        for (int c = 0; c < 8; c++) {
            int file = plateauRetourne ? (7 - c) : c;
            Label lt = makeCoordLabel(String.valueOf((char)('a' + file)), styleCoord, CELL);
            Label lb = makeCoordLabel(String.valueOf((char)('a' + file)), styleCoord, CELL);
            coordTop.getChildren().add(lt);
            coordBot.getChildren().add(lb);
        }

        // Rangs : 8-1 ou 1-8 selon l'orientation
        coordLeft.getChildren().clear();
        coordRight.getChildren().clear();
        for (int r = 0; r < 8; r++) {
            int rang = plateauRetourne ? (r + 1) : (8 - r);
            coordLeft.getChildren().add(makeCoordLabel(String.valueOf(rang), styleCoord, CELL));
            coordRight.getChildren().add(makeCoordLabel(String.valueOf(rang), styleCoord, CELL));
        }
    }

    private Label makeCoordLabel(String text, String styleClass, double size) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setPrefWidth(size);
        l.setPrefHeight(size);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private Player joueurActuel() {
        return (gameState.getSideToMove() == model.Color.WHITE) ? joueurBlanc : joueurNoir;
    }

    private boolean estCoupLegal(Move move) {
        return gameState.getLegalMoves().contains(move);
    }

    private Move trouverCoupVers(Square cible) {
        return coupsDepuiSelection.stream()
            .filter(m -> m.to().equals(cible))
            .findFirst().orElse(null);
    }

    private boolean aPieceDuJoueur(BitboardState bs, Square sq, model.Color side) {
        for (Piece p : Piece.values()) {
            long bb = bs.getBitboard(side, p);
            if ((bb & (1L << sq.index)) != 0) return true;
        }
        return false;
    }

    private boolean aPieceSurCase(BitboardState bs, Square sq) {
        for (model.Color c : model.Color.values())
            for (Piece p : Piece.values())
                if ((bs.getBitboard(c, p) & (1L << sq.index)) != 0) return true;
        return false;
    }

    private Square trouverRoi(BitboardState bs, model.Color side) {
        long bb = bs.getBitboard(side, Piece.KING);
        int idx = Long.numberOfTrailingZeros(bb);
        return Square.fromIndex(idx);
    }

    private static String colLettre(int col) {
        return String.valueOf((char)('a' + col));
    }

    private void afficherInputError(String msg) {
        inputError.setText(msg);
        inputError.setVisible(true);
        inputError.setManaged(true);
    }

    private void cacherInputError() {
        inputError.setVisible(false);
        inputError.setManaged(false);
    }
}
