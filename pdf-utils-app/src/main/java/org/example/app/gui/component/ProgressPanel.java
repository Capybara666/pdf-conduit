package org.example.app.gui.component;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class ProgressPanel extends VBox {

    private final Button runBtn;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label errorBanner;
    private final HBox resultLinks;
    private final Hyperlink openFile;
    private final Hyperlink openFolder;

    public ProgressPanel(String runLabel) {
        setSpacing(8);

        runBtn = new Button(runLabel);
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setMaxWidth(Double.MAX_VALUE);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");

        errorBanner = new Label();
        errorBanner.getStyleClass().add("error-banner");
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.setVisible(false);
        errorBanner.setWrapText(true);

        openFile   = new Hyperlink("Open file");
        openFolder = new Hyperlink("Open folder");
        resultLinks = new HBox(12, openFile, openFolder);
        resultLinks.setVisible(false);

        getChildren().addAll(runBtn, progressBar, statusLabel, errorBanner, resultLinks);
    }

    public Button getRunButton() { return runBtn; }

    public <T> void run(Task<T> task, Path expectedOutput) {
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
        errorBanner.setVisible(false);
        resultLinks.setVisible(false);
        runBtn.setDisable(true);

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            runBtn.setDisable(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Done!");
            resultLinks.setVisible(true);
            openFile.setOnAction(ev -> openPath(expectedOutput));
            openFolder.setOnAction(ev -> openPath(expectedOutput.getParent()));
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            runBtn.setDisable(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("");
            errorBanner.setText(task.getException().getMessage());
            errorBanner.setVisible(true);
        }));

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    // Use the OS file opener via ProcessBuilder rather than java.awt.Desktop:
    // mixing AWT with JavaFX on Linux/GTK installs conflicting X11 error handlers
    // ("XSetErrorHandler() called with a GDK error trap pushed") and crashes the app.
    private void openPath(Path path) {
        if (path == null) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> command;
        if (os.contains("mac")) {
            command = List.of("open", path.toString());
        } else if (os.contains("win")) {
            command = List.of("cmd", "/c", "start", "", path.toString());
        } else {
            command = List.of("xdg-open", path.toString());
        }
        try {
            new ProcessBuilder(command).start();
        } catch (IOException ignored) {}
    }
}
