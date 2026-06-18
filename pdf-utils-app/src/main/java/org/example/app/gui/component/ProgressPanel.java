package org.example.app.gui.component;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.example.app.gui.Animations;
import org.example.app.gui.util.FileOpener;
import org.example.app.gui.util.Sfx;
import org.example.app.i18n.I18n;

import java.nio.file.Path;
import java.util.function.Function;

public class ProgressPanel extends VBox {

    private final Button runBtn;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label errorBanner;
    private final Label warnBanner;
    private final HBox resultLinks;
    private final Button openFile;
    private final Button openFolder;

    public ProgressPanel(String runLabel) {
        setSpacing(8);

        runBtn = new Button(runLabel);
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        Animations.installHoverScale(runBtn, 1.02);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");

        errorBanner = new Label();
        errorBanner.getStyleClass().add("error-banner");
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.setVisible(false);
        errorBanner.setWrapText(true);
        errorBanner.managedProperty().bind(errorBanner.visibleProperty());

        warnBanner = new Label();
        warnBanner.getStyleClass().add("warning-banner");
        warnBanner.setMaxWidth(Double.MAX_VALUE);
        warnBanner.setWrapText(true);
        warnBanner.setVisible(false);
        warnBanner.managedProperty().bind(warnBanner.visibleProperty());

        openFile   = new Button(I18n.t("link.openfile"));
        openFolder = new Button(I18n.t("link.openfolder"));
        openFile.getStyleClass().add("result-link");
        openFolder.getStyleClass().add("result-link");
        resultLinks = new HBox(10, openFile, openFolder);
        resultLinks.getStyleClass().add("result-links");
        resultLinks.setVisible(false);
        resultLinks.managedProperty().bind(resultLinks.visibleProperty());

        // statusLabel only reserves height while it has text.
        statusLabel.managedProperty().bind(statusLabel.textProperty().isNotEmpty());

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
            statusLabel.setText(I18n.t("progress.done"));
            String warning = warningFn == null ? null : warningFn.apply(task.getValue());
            if (warning != null) {
                warnBanner.setText("⚠  " + warning);
                warnBanner.setVisible(true);
                Animations.fadeIn(warnBanner);
            }
            resultLinks.setVisible(true);
            Animations.fadeIn(resultLinks);
            openFile.setOnAction(ev -> FileOpener.open(expectedOutput));
            openFolder.setOnAction(ev -> FileOpener.open(expectedOutput.getParent()));
            Sfx.playDone();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            runBtn.setDisable(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("");
            errorBanner.setText(task.getException().getMessage());
            errorBanner.setVisible(true);
            Sfx.playError();
        }));

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }
}
