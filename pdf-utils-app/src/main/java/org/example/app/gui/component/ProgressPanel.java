package org.example.app.gui.component;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.app.gui.Animations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

public class ProgressPanel extends VBox {

    private final Button runBtn;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label errorBanner;
    private final Label warnBanner;
    private final HBox resultLinks;
    private final Hyperlink openFile;
    private final Hyperlink openFolder;

    public ProgressPanel(String runLabel) {
        setSpacing(8);

        runBtn = new Button(runLabel);
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        Animations.installHoverScale(runBtn, 1.02);

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

        warnBanner = new Label();
        warnBanner.getStyleClass().add("warning-banner");
        warnBanner.setMaxWidth(Double.MAX_VALUE);
        warnBanner.setWrapText(true);
        warnBanner.setVisible(false);
        warnBanner.managedProperty().bind(warnBanner.visibleProperty());

        openFile   = new Hyperlink("Open file");
        openFolder = new Hyperlink("Open folder");
        resultLinks = new HBox(12, openFile, openFolder);
        resultLinks.setVisible(false);

        getChildren().addAll(runBtn, progressBar, statusLabel, errorBanner, warnBanner, resultLinks);
    }

    /** Formats a byte count as a short human-readable string (B / KB / MB). */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.2f MB", kb / 1024.0);
    }

    public Button getRunButton() { return runBtn; }

    public <T> void run(Task<T> task, Path expectedOutput) {
        run(task, expectedOutput, null);
    }

    /**
     * Runs {@code task}, and on success calls {@code warningFn} with the result; if it
     * returns a non-null message, a warning banner is shown alongside the result links.
     */
    public <T> void run(Task<T> task, Path expectedOutput, Function<T, String> warningFn) {
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
        errorBanner.setVisible(false);
        warnBanner.setVisible(false);
        resultLinks.setVisible(false);
        runBtn.setDisable(true);

        task.setOnSucceeded(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            runBtn.setDisable(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("Done!");
            String warning = warningFn == null ? null : warningFn.apply(task.getValue());
            if (warning != null) {
                warnBanner.setText("⚠  " + warning);
                warnBanner.setVisible(true);
                Animations.fadeIn(warnBanner);
            }
            resultLinks.setVisible(true);
            Animations.fadeIn(resultLinks);
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
