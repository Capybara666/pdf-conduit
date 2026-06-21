package com.pdfconduit.app.gui.panels;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.component.DropZone;
import com.pdfconduit.app.gui.component.OutputPathControl;
import com.pdfconduit.app.gui.component.PageReorderGrid;
import com.pdfconduit.app.gui.component.ProgressPanel;
import com.pdfconduit.app.gui.util.DefaultLocations;
import com.pdfconduit.app.gui.util.PdfThumbnails;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.convert.DocumentConverter;
import com.pdfconduit.core.model.ArrangeOptions;
import com.pdfconduit.core.model.ArrangeResult;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.operations.PdfArranger;
import com.pdfconduit.core.service.OperationType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Friendly page-arranging panel: drop in a PDF (or any convertible document),
 * see its pages as thumbnails, then drag them into the order you want — removing
 * or duplicating pages as needed — and save the result. Visual, no syntax to learn.
 *
 * <p>It does not extend {@link BasePanel} (its center is a thumbnail grid, not a
 * file list), but shares the same {@link OutputPathControl} so output handling
 * stays identical to every other panel.
 */
public class ArrangePanel extends BorderPane {

    private static final int THUMB_DPI = 36;

    private final PageReorderGrid grid = new PageReorderGrid();
    private final ProgressPanel progressPanel = new ProgressPanel("run.ARRANGE");
    private final Label statusLabel = new Label();
    private final OutputPathControl output = new OutputPathControl();
    private final Button resetBtn = new Button();
    private final Button reverseBtn = new Button();

    // The PDF currently loaded into the grid (a temp copy when the input was converted).
    private Path workingPdf;
    private final List<Path> workingTemps = new ArrayList<>();
    private String loadedName = "";

    public ArrangePanel() {
        getStyleClass().add("panel-root");

        Label title = new Label();
        I18n.bindText(title::setText, "panel.ARRANGE.title");
        title.getStyleClass().add("panel-title");
        Label hint = new Label();
        I18n.bindText(hint::setText, "hint.ARRANGE");
        hint.getStyleClass().add("text-caption");
        hint.setWrapText(true);

        DropZone dropZone = new DropZone(files -> { if (!files.isEmpty()) loadFile(files.get(0)); });

        statusLabel.getStyleClass().add("text-status");
        // The status text reflects live state; re-derive it on language change
        // (transient loading/error messages are left as-is).
        I18n.addListener(this::refreshControls);
        I18n.bindText(resetBtn::setText, "arrange.reset");
        resetBtn.getStyleClass().add("btn-secondary");
        resetBtn.setOnAction(e -> grid.reset());
        I18n.bindText(reverseBtn::setText, "arrange.reverse");
        reverseBtn.getStyleClass().add("btn-secondary");
        reverseBtn.setOnAction(e -> grid.reverse());
        Region tbSpacer = new Region();
        HBox.setHgrow(tbSpacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, statusLabel, tbSpacer, reverseBtn, resetBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(14, title, hint, dropZone, toolbar);

        progressPanel.getRunButton().setOnAction(e -> onRun());

        VBox bottom = new VBox(14, output, progressPanel);

        setTop(top);
        setCenter(grid);
        setBottom(bottom);
        BorderPane.setMargin(grid, new Insets(14, 0, 14, 0));
        refreshControls();
        grid.setOnChange(this::refreshControls);
    }

    private void refreshControls() {
        boolean has = !grid.isEmpty();
        resetBtn.setDisable(!has);
        reverseBtn.setDisable(!has);
        progressPanel.getRunButton().setDisable(!has);
        statusLabel.setText(has
            ? I18n.t("arrange.loaded", loadedName, grid.pageCount())
            : I18n.t("arrange.nofile"));
    }

    // --- loading + thumbnails ---------------------------------------------

    private void loadFile(Path file) {
        statusLabel.setText(I18n.t("arrange.loading", file.getFileName()));
        Task<List<Image>> task = new Task<>() {
            Path pdf;
            @Override
            protected List<Image> call() throws Exception {
                List<Path> temps = new ArrayList<>();
                pdf = DocumentConverter.ensurePdf(file, PageSize.FIT, temps);
                List<Image> thumbs = PdfThumbnails.render(pdf, THUMB_DPI,
                    (done, total) -> updateMessage(done + "/" + total));
                // Keep the working PDF (and any conversion temps) alive for the run.
                synchronized (workingTemps) {
                    releaseWorking();
                    workingPdf = pdf;
                    workingTemps.addAll(temps);
                }
                return thumbs;
            }
        };
        task.setOnSucceeded(e -> {
            loadedName = file.getFileName().toString();
            grid.setPages(task.getValue());
            refreshControls();
            // Default the output name to "<stem>_arranged.pdf".
            output.suggestName(stripExt(loadedName) + OperationType.ARRANGE.suffix() + ".pdf");
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            statusLabel.setText(I18n.t("arrange.loadfail",
                ex == null ? "?" : ex.getMessage()));
        });
        Thread t = new Thread(task, "arrange-load");
        t.setDaemon(true);
        t.start();
    }

    private void onRun() {
        if (workingPdf == null || grid.isEmpty()) return;
        List<Integer> order = grid.order();
        Path source = workingPdf;
        Path dest = output.resolveOutput(DefaultLocations.DEFAULT_FILE);
        Task<ArrangeResult> task = new Task<>() {
            @Override
            protected ArrangeResult call() throws Exception {
                updateMessage(I18n.t("arrange.saving"));
                return PdfArranger.execute(new ArrangeOptions(source, order, dest));
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

    // --- utilities --------------------------------------------------------

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
