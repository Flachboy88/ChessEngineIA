package ui.controller;

import game.GameState;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import model.Color;
import player.*;
import player.classical.AlphaBetaPlayer;
import player.classical.HumanPlayer;
import player.classical.RandomAIPlayer;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Controller de la vue Accueil.
 * Gère la sélection du mode de jeu, des joueurs, de l'IA et de la position FEN.
 */
public class AccueilController {

    // ── Mode de jeu ──────────────────────────────────────────────────────────
    @FXML private ToggleButton btnHvsH;
    @FXML private ToggleButton btnHvsIA;
    @FXML private ToggleButton btnIAvsIA;

    // ── Choix couleur (H vs IA) ───────────────────────────────────────────
    @FXML private VBox configCouleur;
    @FXML private Region sepCouleur;
    @FXML private ToggleButton btnJouerBlanc;
    @FXML private ToggleButton btnJouerNoir;

    // ── Blanc ─────────────────────────────────────────────────────────────────
    @FXML private TextField        nomBlanc;
    @FXML private ComboBox<String> iaBlanc;
    @FXML private HBox             rowIABlanc;
    @FXML private Spinner<Integer> timeBlanc;
    @FXML private HBox             rowTimeBlanc;

    // ── Noir ──────────────────────────────────────────────────────────────────
    @FXML private TextField        nomNoir;
    @FXML private ComboBox<String> iaNoir;
    @FXML private HBox             rowIANoir;
    @FXML private Spinner<Integer> timeNoir;
    @FXML private HBox             rowTimeNoir;

    // ── FEN ───────────────────────────────────────────────────────────────────
    @FXML private TextField fenField;
    @FXML private Label     fenError;

    // ── Lancer ────────────────────────────────────────────────────────────────
    @FXML private Button btnLancer;

    // Groupe exclusif pour les toggle buttons
    private ToggleGroup modeGroup;

    // Mode actuel
    private enum Mode { H_VS_H, H_VS_IA, IA_VS_IA }
    private Mode modeActuel = Mode.H_VS_H;

    /** Temps de réflexion par défaut en secondes. */
    private static final int DEFAULT_TIME_SEC = (int) (AlphaBetaPlayer.DEFAULT_TIME_MS / 1000);

    /** Noms affichés dans les ComboBox IA — l'ordre doit correspondre à {@link #creerIA}. */
    private static final String[] IA_OPTIONS = {
        "AlphaBeta (recommandée)",
        "Aléatoire"
    };

    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Groupe de toggle exclusif
        modeGroup = new ToggleGroup();
        btnHvsH.setToggleGroup(modeGroup);
        btnHvsIA.setToggleGroup(modeGroup);
        btnIAvsIA.setToggleGroup(modeGroup);
        btnHvsH.setSelected(true);

        // Groupe exclusif pour le choix de couleur
        ToggleGroup couleurGroup = new ToggleGroup();
        btnJouerBlanc.setToggleGroup(couleurGroup);
        btnJouerNoir.setToggleGroup(couleurGroup);
        btnJouerBlanc.setSelected(true); // blanc par défaut

        // Remplir les ComboBox IA
        iaBlanc.getItems().addAll(IA_OPTIONS);
        iaNoir.getItems().addAll(IA_OPTIONS);
        iaBlanc.getSelectionModel().selectFirst();
        iaNoir.getSelectionModel().selectFirst();

        // Spinners de temps (1–60 secondes, défaut = DEFAULT_TIME_SEC)
        SpinnerValueFactory<Integer> svfBlanc = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, DEFAULT_TIME_SEC);
        SpinnerValueFactory<Integer> svfNoir  = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, DEFAULT_TIME_SEC);
        timeBlanc.setValueFactory(svfBlanc);
        timeNoir.setValueFactory(svfNoir);
        timeBlanc.setEditable(true);
        timeNoir.setEditable(true);

        // Masquer/afficher le spinner selon l'IA sélectionnée
        iaBlanc.getSelectionModel().selectedIndexProperty().addListener(
            (obs, o, n) -> rowTimeBlanc.setVisible(n.intValue() == 0));
        iaNoir.getSelectionModel().selectedIndexProperty().addListener(
            (obs, o, n) -> rowTimeNoir.setVisible(n.intValue() == 0));

        // Valeurs par défaut des noms
        nomBlanc.setText("Joueur 1");
        nomNoir.setText("Joueur 2");

        // Affichage initial
        appliquerMode(Mode.H_VS_H);
    }

    // ── Callbacks FXML ───────────────────────────────────────────────────────

    @FXML
    private void onModeChanged() {
        Toggle sel = modeGroup.getSelectedToggle();
        if (sel == btnHvsH)        appliquerMode(Mode.H_VS_H);
        else if (sel == btnHvsIA)  appliquerMode(Mode.H_VS_IA);
        else if (sel == btnIAvsIA) appliquerMode(Mode.IA_VS_IA);
    }

    @FXML
    private void onCouleurChanged() {
        // Empêche la désélection totale et met à jour l'affichage IA/Humain
        if (btnJouerBlanc.getToggleGroup().getSelectedToggle() == null) {
            btnJouerBlanc.setSelected(true);
        }
        if (modeActuel == Mode.H_VS_IA) appliquerMode(Mode.H_VS_IA);
    }

    @FXML
    private void onResetFen() {
        fenField.clear();
        cacherErreurFen();
    }

    @FXML
    private void onLancerPartie() {
        cacherErreurFen();

        // Valider FEN si renseigné
        String fen = fenField.getText().trim();
        GameState gameState;
        try {
            gameState = fen.isEmpty()
                    ? new GameState()
                    : new GameState(game.FenParser.parse(fen));
        } catch (Exception e) {
            afficherErreurFen("Position FEN invalide : " + e.getMessage());
            return;
        }

        // Construire les joueurs
        Player blanc = creerJoueur(Color.WHITE, modeActuel, true);
        Player noir  = creerJoueur(Color.BLACK, modeActuel, false);

        // Ouvrir la vue Partie
        try {
            URL fxmlUrl = getClass().getResource("/ui/view/partie.fxml");
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(fxmlUrl));
            Parent root = loader.load();

            PartieController partieCtrl = loader.getController();
            partieCtrl.initialiser(gameState, blanc, noir);

            Stage stage = (Stage) btnLancer.getScene().getWindow();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/ui/view/chess.css")).toExternalForm()
            );
            stage.setScene(scene);
            stage.setTitle("ChessOptiIA — Partie");
            stage.setResizable(false);
            stage.sizeToScene();

        } catch (IOException e) {
            afficherErreurFen("Erreur interne : impossible d'ouvrir la vue partie.");
            e.printStackTrace();
        }
    }

    // ── Méthodes privées ─────────────────────────────────────────────────────

    private void appliquerMode(Mode mode) {
        modeActuel = mode;
        boolean couleurVisible = (mode == Mode.H_VS_IA);
        configCouleur.setVisible(couleurVisible);
        configCouleur.setManaged(couleurVisible);
        sepCouleur.setVisible(couleurVisible);
        sepCouleur.setManaged(couleurVisible);

        switch (mode) {
            case H_VS_H -> {
                setIAVisible(false, false);
                nomBlanc.setPromptText("Ex : Alice");
                nomNoir.setPromptText("Ex : Bob");
            }
            case H_VS_IA -> {
                boolean joueurEstBlanc = btnJouerBlanc.isSelected();
                setIAVisible(!joueurEstBlanc, joueurEstBlanc);
                nomBlanc.setPromptText(joueurEstBlanc ? "Votre nom" : "Nom de l'IA");
                nomNoir.setPromptText(joueurEstBlanc ? "Nom de l'IA" : "Votre nom");
            }
            case IA_VS_IA -> {
                setIAVisible(true, true);
                nomBlanc.setPromptText("Nom IA Blanc");
                nomNoir.setPromptText("Nom IA Noir");
            }
        }
    }

    /** Affiche ou masque les lignes "IA" et "Temps" selon le mode. */
    private void setIAVisible(boolean blancsIA, boolean noirIA) {
        rowIABlanc.setVisible(blancsIA);
        rowIABlanc.setManaged(blancsIA);
        rowTimeBlanc.setVisible(blancsIA && iaBlanc.getSelectionModel().getSelectedIndex() == 0);
        rowTimeBlanc.setManaged(blancsIA);

        rowIANoir.setVisible(noirIA);
        rowIANoir.setManaged(noirIA);
        rowTimeNoir.setVisible(noirIA && iaNoir.getSelectionModel().getSelectedIndex() == 0);
        rowTimeNoir.setManaged(noirIA);
    }

    /** Crée un joueur (humain ou IA) selon le mode et la configuration du formulaire. */
    private Player creerJoueur(Color color, Mode mode, boolean isBlanc) {
        boolean estIA;
        if (mode == Mode.IA_VS_IA) {
            estIA = true;
        } else if (mode == Mode.H_VS_IA) {
            boolean joueurEstBlanc = btnJouerBlanc.isSelected();
            estIA = (isBlanc != joueurEstBlanc);
        } else {
            estIA = false;
        }

        String nom = (isBlanc ? nomBlanc : nomNoir).getText().trim();
        if (nom.isEmpty()) nom = (color == Color.WHITE ? "Blanc" : "Noir");

        if (!estIA) {
            return new HumanPlayer(color, nom);
        }

        // Créer l'IA selon la sélection
        ComboBox<String> combo = isBlanc ? iaBlanc : iaNoir;
        return creerIA(color, nom, combo.getSelectionModel().getSelectedIndex(),
                       isBlanc ? timeBlanc : timeNoir);
    }

    /**
     * Factory d'IA selon l'index sélectionné dans la ComboBox.
     * L'ordre doit correspondre à {@link #IA_OPTIONS}.
     *
     * @param color       couleur du camp
     * @param nom         nom affiché
     * @param iaIndex     index sélectionné (0=AlphaBeta, 1=Random)
     * @param timeSpinner spinner de temps en secondes (utilisé uniquement pour AlphaBeta)
     */
    private Player creerIA(Color color, String nom, int iaIndex, Spinner<Integer> timeSpinner) {
        return switch (iaIndex) {
            case 0 -> {  // AlphaBeta
                long timeLimitMs = timeSpinner.getValue() * 1000L;
                yield new AlphaBetaPlayer(color, timeLimitMs, nom + " (" + timeSpinner.getValue() + "s)");
            }
            case 1 -> new RandomAIPlayer(color, nom, new java.util.Random()); // Random
            default -> new RandomAIPlayer(color, nom, new java.util.Random());
        };
    }

    private void afficherErreurFen(String msg) {
        fenError.setText(msg);
        fenError.setVisible(true);
        fenError.setManaged(true);
    }

    private void cacherErreurFen() {
        fenError.setVisible(false);
        fenError.setManaged(false);
    }
}
