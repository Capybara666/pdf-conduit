package com.pdfconduit.app.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class PdfUtilsApp extends Application {

    @Override
    public void start(Stage stage) {
        new MainWindow(stage).show();
        Platform.runLater(() -> stage.getScene().getRoot().layout());
    }

    public static void launchApp(String[] args) {
        launch(args);
    }
}
