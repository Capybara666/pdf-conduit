package com.pdfconduit.app.gui.component;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Animations;
import com.pdfconduit.app.gui.util.FileOpener;
import com.pdfconduit.app.gui.util.PreviewWindow;
import com.pdfconduit.app.gui.util.Settings;
import com.pdfconduit.app.gui.util.Sfx;
import com.pdfconduit.app.i18n.I18n;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

public class ProgressPanel extends VBox {

    private final Button runBtn;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label errorBanner;
    private final Label warnBanner;
    private final Label summaryLabel;
    private final HBox resultLinks;
    private final Button previewBtn;
    private final Button openFile;
    private final Button openFolder;

    public ProgressPanel(String runKey) {
        setSpacing(8);

        runBtn = new Button();
        I18n.bindText(runBtn::setText, runKey);
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        Animations.installHoverScale(runBtn, 1.02);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.managedProperty().bind(progressBar.visibleProperty());

        statusLabel = new Label();
        statusLabel.getStyleClass().add("text-status");

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

        previewBtn = new Button();
        openFile   = new Button();
        openFolder = new Button();
        I18n.bindText(previewBtn::setText, "link.preview");
        I18n.bindText(openFile::setText, "link.openfile");
        I18n.bindText(openFolder::setText, "link.openfolder");
        previewBtn.getStyleClass().add("result-link");
        openFile.getStyleClass().add("result-link");
        openFolder.getStyleClass().add("result-link");
        // Preview and "Open file" only make sense for a single-file output (not a folder).
        previewBtn.managedProperty().bind(previewBtn.visibleProperty());
        openFile.managedProperty().bind(openFile.visibleProperty());
        resultLinks = new HBox(10, previewBtn, openFile, openFolder);
        resultLinks.getStyleClass().add("result-links");
        resultLinks.setVisible(false);
        resultLinks.managedProperty().bind(resultLinks.visibleProperty());

        summaryLabel = new Label();
        summaryLabel.getStyleClass().add("result-summary");
        summaryLabel.setVisible(false);
        summaryLabel.managedProperty().bind(summaryLabel.visibleProperty());

        // statusLabel only reserves height while it has text.
        statusLabel.managedProperty().bind(statusLabel.textProperty().isNotEmpty());

        getChildren().addAll(runBtn, progressBar, statusLabel, errorBanner, warnBanner,
            summaryLabel, resultLinks);
    }

    /** A generic "output is X" line for a single-PDF result, or {@code null} otherwise. */
    private static String genericSummary(Path output) {
        if (!isPreviewablePdf(output)) return null;
        try {
            return I18n.t("summary.size", humanSize(Files.size(output)));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when {@code p} is a single existing PDF file — the only case in-app preview fits. */
    private static boolean isPreviewablePdf(Path p) {
        return p != null && Files.isRegularFile(p)
            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** A non-null, human-readable message for a failed task's exception. */
    static String messageOf(Throwable t) {
        if (t == null) return "Operation failed.";
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
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
        run(task, expectedOutput, null, null);
    }

    public <T> void run(Task<T> task, Path expectedOutput, Function<T, String> warningFn) {
        run(task, expectedOutput, warningFn, null);
    }

    /**
     * Runs {@code task}. On success: shows the result links; calls {@code warningFn} for
     * an optional warning banner; and shows a result summary — {@code summaryFn}'s text if
     * given (e.g. Compress's "12 MB → 3 MB (−73%)"), else a generic output-size line for a
     * single-PDF result. Both functions may be {@code null}.
     */
    public <T> void run(Task<T> task, Path expectedOutput,
                        Function<T, String> warningFn, Function<T, String> summaryFn) {
        progressBar.setVisible(true);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
        errorBanner.setVisible(false);
        warnBanner.setVisible(false);
        summaryLabel.setVisible(false);
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
            String summary = summaryFn == null ? null : summaryFn.apply(task.getValue());
            if (summary == null) summary = genericSummary(expectedOutput);
            if (summary != null) {
                summaryLabel.setText(summary);
                summaryLabel.setVisible(true);
            }
            resultLinks.setVisible(true);
            Animations.popIn(resultLinks);
            // A folder output (Extract-separate, batch, PDF→images) IS the folder; a
            // single-file output lives inside its parent. "Open file" only applies to
            // the latter; "Open folder" must open the containing folder either way.
            boolean isDir = Files.isDirectory(expectedOutput);
            Path folder = isDir ? expectedOutput : expectedOutput.getParent();
            boolean previewable = isPreviewablePdf(expectedOutput);
            previewBtn.setVisible(previewable);
            if (previewable) previewBtn.setOnAction(ev -> PreviewWindow.open(this, expectedOutput));
            openFile.setVisible(!isDir);
            if (!isDir) openFile.setOnAction(ev -> FileOpener.open(expectedOutput));
            openFolder.setOnAction(ev -> FileOpener.open(folder));
            switch (Settings.autoOpen()) {
                case FILE   -> FileOpener.open(isDir ? folder : expectedOutput);
                case FOLDER -> FileOpener.open(folder);
                case NONE   -> { /* leave it to the result links */ }
            }
            Sfx.playDone();
        }));

        task.setOnFailed(e -> Platform.runLater(() -> {
            progressBar.setVisible(false);
            runBtn.setDisable(false);
            statusLabel.textProperty().unbind();
            statusLabel.setText("");
            errorBanner.setText(messageOf(task.getException()));
            errorBanner.setVisible(true);
            Sfx.playError();
        }));

        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }
}
