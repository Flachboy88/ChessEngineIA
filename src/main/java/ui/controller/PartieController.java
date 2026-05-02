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
import javafx.stage.Stage;
import javafx.util.Duration;
import model.Move;
import model.Piece;
import model.Square;
import player.HumanPlayer;
import player.Player;
import rules.MoveGenerator;

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

    /** Historique pour affichage (notation UCI simplifiée) */
    private int      histoMoveCount = 0;

    // ── Constantes plateau ────────────────────────────────────────────────────
    private static final double CELL = 70.0;
    private static final double BOARD = CELL * 8;

    // Palette (hex → JavaFX Color)
    private static final Color C_LIGHT      = Color.web("#f0d9b5");
    private static final Color C_DARK       = Color.web("#b58863");
    private static final Color C_SELECT     = Color.web("#7fc97f", 0.6);
    private static final Color C_LAST_MOVE  = Color.web("#cdd16e", 0.55);
    private static final Color C_LEGAL_DOT  = Color.web("#3d3318", 0.4);
    private static final Color C_LEGAL_RING = Color.web("#3d3318", 0.3);
    private static final Color C_CHECK      = Color.web("#c0392b", 0.45);

    // Symboles Unicode pièces (blanc=majuscule, noir=minuscule en logique ici)
    private static final String[][] PIECE_UNICODE = {
        // WHITE: P  N  B  R  Q  K
        { "♙", "♘", "♗", "♖", "♕", "♔" },
        // BLACK: p  n  b  r  q  k
        { "♟", "♞", "♝", "♜", "♛", "♚" }
    };

    // ── Exécuteur IA ─────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ia-thread");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Appelé par AccueilController après chargement du FXML.
     */
    public void initialiser(GameState gameState, Player blanc, Player noir) {
        this.gameState    = gameState;
        this.joueurBlanc  = blanc;
        this.joueurNoir   = noir;

        construireCoordonnees();
        mettreAJourUI();
        jouerTourSiIA();
    }

    @FXML
    public void initialize() {
        // Clic sur le canvas pour les humains
        boardCanvas.setOnMouseClicked(e -> {
            int col = (int)(e.getX() / CELL);
            int row = (int)(e.getY() / CELL);
            if (col < 0 || col > 7 || row < 0 || row > 7) return;
            onClicCase(col, 7 - row); // row inversé : rang 0 = bas
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
            jouerCoup(move);
            inputField.clear();
        } catch (IllegalArgumentException ex) {
            afficherInputError("Notation invalide (ex: e2e4)");
        }
    }

    @FXML
    private void onUndo() {
        if (gameState.undo()) {
            dernierCoup = null;
            caseSelectionnee = null;
            coupsDepuiSelection = List.of();
            mettreAJourUI();
        }
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

    /** Gère un clic sur la case (col, rang) — coordonnées plateau (0=a, 0=1). */
    private void onClicCase(int col, int rang) {
        if (gameState.getResult().isOver()) return;

        Player joueurActuel = joueurActuel();
        if (!(joueurActuel instanceof HumanPlayer)) return;

        Square caseClic = Square.fromAlgebraic(colLettre(col) + (rang + 1));

        // Cas 1 : une case est déjà sélectionnée → tenter le coup
        if (caseSelectionnee != null) {
            Move coup = trouverCoupVers(caseClic);
            if (coup != null) {
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

    /** Joue le coup, met à jour l'UI et déclenche le tour suivant si IA. */
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

    /** Déclenche le coup IA dans un thread séparé si c'est son tour. */
    private void jouerTourSiIA() {
        Player joueur = joueurActuel();
        if (joueur instanceof HumanPlayer) return;
        if (gameState.getResult().isOver()) return;

        // Délai visuel de 400ms pour que l'IA ne joue pas instantanément
        Timeline delay = new Timeline(new KeyFrame(Duration.millis(400), e -> {
            executor.submit(() -> {
                Move coup = joueur.getNextMove(gameState);
                Platform.runLater(() -> jouerCoup(coup));
            });
        }));
        delay.play();
    }

    // ── Rendu plateau ─────────────────────────────────────────────────────────

    private void dessinerPlateau() {
        GraphicsContext gc = boardCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BOARD, BOARD);

        BitboardState bs = gameState.getBitboardState();
        List<Move> legaux = gameState.getLegalMoves();

        for (int rang = 7; rang >= 0; rang--) {
            for (int col = 0; col < 8; col++) {
                double x = col * CELL;
                double y = (7 - rang) * CELL;
                Square sq = Square.fromAlgebraic(colLettre(col) + (rang + 1));

                // Couleur de base de la case
                Color bg = ((col + rang) % 2 == 0) ? C_DARK : C_LIGHT;

                // Highlight dernier coup
                if (dernierCoup != null &&
                    (sq.equals(dernierCoup.from()) || sq.equals(dernierCoup.to()))) {
                    bg = C_LAST_MOVE;
                }
                // Highlight sélection
                if (sq.equals(caseSelectionnee)) bg = C_SELECT;

                // Highlight roi en échec
                if (gameState.isCheck()) {
                    Square roiSq = trouverRoi(bs, gameState.getSideToMove());
                    if (sq.equals(roiSq)) bg = C_CHECK;
                }

                gc.setFill(bg);
                gc.fillRect(x, y, CELL, CELL);

                // Cercles des coups légaux
                if (caseSelectionnee != null) {
                    boolean estCibleLegale = coupsDepuiSelection.stream()
                        .anyMatch(m -> m.to().equals(sq));
                    if (estCibleLegale) {
                        boolean aPieceCible = aPieceSurCase(bs, sq);
                        if (aPieceCible) {
                            // Anneau autour des captures
                            gc.setStroke(C_LEGAL_RING);
                            gc.setLineWidth(4);
                            gc.strokeOval(x + 3, y + 3, CELL - 6, CELL - 6);
                        } else {
                            // Point central pour déplacements
                            gc.setFill(C_LEGAL_DOT);
                            double r = CELL * 0.16;
                            gc.fillOval(x + CELL/2 - r, y + CELL/2 - r, r*2, r*2);
                        }
                    }
                }

                // Dessin de la pièce
                dessinePiece(gc, bs, sq, x, y);
            }
        }
    }

    /** Dessine la pièce sur la case donnée si elle existe. */
    private void dessinePiece(GraphicsContext gc, BitboardState bs, Square sq,
                               double x, double y) {
        for (model.Color c : model.Color.values()) {
            for (Piece p : Piece.values()) {
                long bb = bs.getBitboard(c, p);
                if ((bb & (1L << sq.index)) != 0) {
                    int ci = c.ordinal();  // 0=WHITE, 1=BLACK
                    String sym = PIECE_UNICODE[ci][p.index];

                    gc.setFont(Font.font("Segoe UI Symbol", CELL * 0.72));
                    // Ombre légère
                    gc.setFill(Color.color(0, 0, 0, 0.28));
                    gc.fillText(sym, x + CELL * 0.13 + 1, y + CELL * 0.82 + 1);
                    // Pièce
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

        // Indicateur couleur
        turnIndicator.getStyleClass().removeAll("turn-indicator-white", "turn-indicator-black");
        turnIndicator.getStyleClass().add(estBlanc ? "turn-indicator-white" : "turn-indicator-black");

        // Alerte échec
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
        int paire = (moveCount - 1) / 2; // numéro de paire (base 0)

        if (estBlanc) {
            // Numéro
            Label num = new Label((paire + 1) + ".");
            num.getStyleClass().add("move-num");
            historiqueGrid.add(num, 0, paire);
        }

        Label cell = new Label(coup.toUci().toUpperCase());
        cell.getStyleClass().add("move-cell");

        // Marquer le dernier coup en or
        historiqueGrid.getChildren().forEach(n -> {
            if (n instanceof Label l && l.getStyleClass().contains("move-cell-last")) {
                l.getStyleClass().remove("move-cell-last");
            }
        });
        cell.getStyleClass().add("move-cell-last");

        historiqueGrid.add(cell, estBlanc ? 1 : 2, paire);

        // Scroll automatique vers le bas
        Platform.runLater(() -> historiqueScroll.setVvalue(1.0));
    }

    // ── Coordonnées plateau ───────────────────────────────────────────────────

    private void construireCoordonnees() {
        String styleCoord = "coord-label";

        // Colonnes a-h (haut et bas)
        coordTop.getChildren().clear();
        coordBot.getChildren().clear();
        Region spacerL = new Region();
        spacerL.setPrefWidth(20);
        Region spacerL2 = new Region();
        spacerL2.setPrefWidth(20);
        coordTop.getChildren().add(spacerL);
        coordBot.getChildren().add(spacerL2);

        for (int c = 0; c < 8; c++) {
            Label lt = makeCoordLabel(String.valueOf((char)('a' + c)), styleCoord, CELL);
            Label lb = makeCoordLabel(String.valueOf((char)('a' + c)), styleCoord, CELL);
            coordTop.getChildren().add(lt);
            coordBot.getChildren().add(lb);
        }

        // Rangs 8-1 (gauche et droite)
        coordLeft.getChildren().clear();
        coordRight.getChildren().clear();
        for (int r = 7; r >= 0; r--) {
            coordLeft.getChildren().add(makeCoordLabel(String.valueOf(r + 1), styleCoord, CELL));
            coordRight.getChildren().add(makeCoordLabel(String.valueOf(r + 1), styleCoord, CELL));
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
