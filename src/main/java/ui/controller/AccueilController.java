package ui.controller;

import game.GameState;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import model.Color;
import player.*;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Controller de la vue Accueil.
 * Gère la sélection du mode de jeu, des joueurs et de la position FEN.
 */
public class AccueilController {

    // ── Mode de jeu ──────────────────────────────────────────────────────────
    @FXML private ToggleButton btnHvsH;
    @FXML private ToggleButton btnHvsIA;
    @FXML private ToggleButton btnIAvsIA;

    // ── Blanc ─────────────────────────────────────────────────────────────────
    @FXML private TextField    nomBlanc;
    @FXML private ComboBox<String> iaBlanc;
    @FXML private HBox         rowIABlanc;

    // ── Noir ──────────────────────────────────────────────────────────────────
    @FXML private TextField    nomNoir;
    @FXML private ComboBox<String> iaNoir;
    @FXML private HBox         rowIANoir;

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

    // ─────────────────────────────────────────────────────────────────────────

    @FXML
    public void initialize() {
        // Groupe de toggle exclusif
        modeGroup = new ToggleGroup();
        btnHvsH.setToggleGroup(modeGroup);
        btnHvsIA.setToggleGroup(modeGroup);
        btnIAvsIA.setToggleGroup(modeGroup);
        btnHvsH.setSelected(true);

        // Remplir les ComboBox IA
        String[] iaOptions = { "Random AI", /* ajouter ici les futures IA */ };
        iaBlanc.getItems().addAll(iaOptions);
        iaNoir.getItems().addAll(iaOptions);
        iaBlanc.getSelectionModel().selectFirst();
        iaNoir.getSelectionModel().selectFirst();

        // Valeurs par défaut
        nomBlanc.setText("Joueur 1");
        nomNoir.setText("Joueur 2");

        // Affichage initial
        appliquerMode(Mode.H_VS_H);
    }

    // ── Callbacks FXML ───────────────────────────────────────────────────────

    @FXML
    private void onModeChanged() {
        Toggle sel = modeGroup.getSelectedToggle();
        if (sel == btnHvsH)    appliquerMode(Mode.H_VS_H);
        else if (sel == btnHvsIA)  appliquerMode(Mode.H_VS_IA);
        else if (sel == btnIAvsIA) appliquerMode(Mode.IA_VS_IA);
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
        switch (mode) {
            case H_VS_H -> {
                setIAVisible(false, false);
                nomBlanc.setPromptText("Ex : Alice");
                nomNoir.setPromptText("Ex : Bob");
            }
            case H_VS_IA -> {
                setIAVisible(false, true);
                nomBlanc.setPromptText("Votre nom");
                nomNoir.setPromptText("Nom de l'IA");
            }
            case IA_VS_IA -> {
                setIAVisible(true, true);
                nomBlanc.setPromptText("Nom IA Blanc");
                nomNoir.setPromptText("Nom IA Noir");
            }
        }
    }

    /** Affiche ou masque les lignes "IA" selon le mode. */
    private void setIAVisible(boolean blancsIA, boolean noirIA) {
        rowIABlanc.setVisible(blancsIA);
        rowIABlanc.setManaged(blancsIA);
        rowIANoir.setVisible(noirIA);
        rowIANoir.setManaged(noirIA);
    }

    /** Crée un joueur (humain ou IA) selon le mode. */
    private Player creerJoueur(Color color, Mode mode, boolean isBlanc) {
        boolean estIA = (mode == Mode.IA_VS_IA)
                || (mode == Mode.H_VS_IA && !isBlanc);

        String nom = isBlanc
                ? nomBlanc.getText().trim()
                : nomNoir.getText().trim();
        if (nom.isEmpty()) nom = (color == Color.WHITE ? "Blanc" : "Noir");

        if (estIA) {
            // Pour l'instant une seule IA disponible
            return new RandomAIPlayer(color, nom, new java.util.Random());
        } else {
            return new HumanPlayer(color, nom);
        }
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
