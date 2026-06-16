package org.example.app.gui;

import javafx.application.Application;
import javafx.stage.Stage;

public class PdfUtilsApp extends Application {

    @Override
    public void start(Stage stage) {
        new MainWindow(stage).show();
    }

    public static void launchApp(String[] args) {
        launch(args);
    }
}
