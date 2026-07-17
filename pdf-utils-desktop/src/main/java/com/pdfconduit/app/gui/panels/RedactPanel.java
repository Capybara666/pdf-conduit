package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.component.DropZone;
import com.pdfconduit.app.gui.component.OutputPathControl;
import com.pdfconduit.app.gui.component.PdfViewer;
import com.pdfconduit.app.gui.component.ProgressPanel;
import com.pdfconduit.app.gui.util.DefaultLocations;
import com.pdfconduit.app.gui.util.OutputGuard;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.RedactOptions;
import com.pdfconduit.core.model.RedactResult;
import com.pdfconduit.core.operations.PdfRedactor;
import com.pdfconduit.core.service.OperationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive redaction: drop in a PDF (or any convertible document), drag black
 * rectangles over the parts to remove on the embedded {@link PdfViewer}, and save.
 * Redaction is <b>permanent</b> — each page that carries a rectangle is flattened
 * to an image with the regions painted out, so no hidden text survives underneath
 * (see {@link PdfRedactor}). Pages left untouched keep their searchable text.
 *
 * <p>Like {@link ArrangePanel} it does not extend {@link BasePanel} (its centre is
 * a viewer, not a file list), but shares the same {@link OutputPathControl} and
 * {@link ProgressPanel} so output handling matches every other panel.
 */
public class RedactPanel extends BorderPane {

    private final PdfViewer viewer = new PdfViewer();
    private final ProgressPanel progressPanel = new ProgressPanel("run.REDACT");
    private final OutputPathControl output = new OutputPathControl();
    private final Label statusLabel = new Label();
    private final Button clearBtn = new Button();

    // The PDF currently shown (a temp copy when the input was converted).
    private Path workingPdf;
    private final List<Path> workingTemps = new ArrayList<>();
    private String loadedName = "";

    public RedactPanel() {
        getStyleClass().add("panel-root");

        Label title = new Label();
        I18n.bindText(title::setText, "panel.REDACT.title");
        title.getStyleClass().add("panel-title");
        Label hint = new Label();
        I18n.bindText(hint::setText, "hint.REDACT");
        hint.getStyleClass().add("text-caption");
        hint.setWrapText(true);

        DropZone dropZone = new DropZone(files -> { if (!files.isEmpty()) loadFile(files.get(0)); });

        statusLabel.getStyleClass().add("text-status");
        I18n.addListener(this::refreshControls);
        I18n.bindText(clearBtn::setText, "redact.clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> viewer.clearRegions());
        Region tbSpacer = new Region();
        HBox.setHgrow(tbSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, statusLabel, tbSpacer, clearBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(14, title, hint, dropZone, toolbar);

        viewer.setRedactionMode(true);
        viewer.setOnRegionsChanged(this::refreshControls);

        Label warn = new Label();
        I18n.bindText(warn::setText, "redact.permanent");
        warn.getStyleClass().add("text-caption");
        warn.setWrapText(true);

        progressPanel.getRunButton().setOnAction(e -> onRun());

        VBox bottom = new VBox(14, warn, output, progressPanel);

        setTop(top);
        setCenter(viewer);
        setBottom(bottom);
        BorderPane.setMargin(viewer, new Insets(14, 0, 14, 0));
        refreshControls();
    }

    private void refreshControls() {
        boolean hasFile = workingPdf != null;
        int regions = viewer.regionCount();
        clearBtn.setDisable(regions == 0);
        progressPanel.getRunButton().setDisable(!hasFile || regions == 0);
        if (!hasFile) {
            statusLabel.setText(I18n.t("redact.nofile"));
        } else {
            statusLabel.setText(I18n.t("redact.loaded", loadedName, regions));
        }
    }

    // --- loading ----------------------------------------------------------

    private void loadFile(Path file) {
        statusLabel.setText(I18n.t("redact.loading", file.getFileName()));
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                List<Path> temps = new ArrayList<>();
                Path pdf = DocumentConverter.ensurePdf(file, PageSize.FIT, temps);
                synchronized (workingTemps) {
                    releaseWorking();
                    workingPdf = pdf;
                    workingTemps.addAll(temps);
                }
                return pdf;
            }
        };
        task.setOnSucceeded(e -> {
            loadedName = file.getFileName().toString();
            viewer.load(task.getValue());           // also clears any prior regions
            output.suggestName(stripExt(loadedName) + OperationType.REDACT.suffix() + ".pdf");
            refreshControls();
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            statusLabel.setText(I18n.t("redact.loadfail", ex == null ? "?" : ex.getMessage()));
        });
        Thread t = new Thread(task, "redact-load");
        t.setDaemon(true);
        t.start();
    }

    private void onRun() {
        if (workingPdf == null || viewer.regionCount() == 0) return;
        Path source = workingPdf;
        var regions = viewer.regions();
        Path dest = OutputGuard.confirm(this, output.resolveOutput(DefaultLocations.DEFAULT_FILE)).orElse(null);
        if (dest == null) return;
        Task<RedactResult> task = new Task<>() {
            @Override
            protected RedactResult call() throws Exception {
                updateMessage(I18n.t("redact.saving"));
                return PdfRedactor.execute(new RedactOptions(
                    source, regions, PdfRedactor.DEFAULT_DPI, dest));
            }
        };
        progressPanel.run(task, dest);
    }

    private void releaseWorking() {
        for (Path t : workingTemps) {
            try { Files.deleteIfExists(t); } catch (Exception ignored) {}
        }
        workingTemps.clear();
        workingPdf = null;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
