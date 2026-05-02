import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Objects;

/**
 * Point d'entrée JavaFX de ChessOptiIA.
 */
public class MainFx extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        URL fxmlUrl = Objects.requireNonNull(
            getClass().getResource("/ui/view/accueil.fxml"),
            "Impossible de trouver accueil.fxml dans les resources"
        );

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
            Objects.requireNonNull(
                getClass().getResource("/ui/view/chess.css"),
                "chess.css introuvable"
            ).toExternalForm()
        );

        stage.setTitle("ChessOptiIA");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.sizeToScene();
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
