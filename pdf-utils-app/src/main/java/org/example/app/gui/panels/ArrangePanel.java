package org.example.app.gui.panels;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.example.app.gui.component.DropZone;
import org.example.app.gui.component.PageReorderGrid;
import org.example.app.gui.component.ProgressPanel;
import org.example.app.gui.util.OutputPaths;
import org.example.app.gui.util.PdfThumbnails;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.ArrangeOptions;
import org.example.core.model.ArrangeResult;
import org.example.core.model.PageSize;
import org.example.core.operations.PdfArranger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Friendly page-arranging panel: drop in a PDF (or any convertible document),
 * see its pages as thumbnails, then drag them into the order you want — removing
 * or duplicating pages as needed — and save the result. Visual, no syntax to learn.
 */
public class ArrangePanel extends BorderPane {

    private static final int THUMB_DPI = 36;

    private final PageReorderGrid grid = new PageReorderGrid();
    private final ProgressPanel progressPanel = new ProgressPanel("run.ARRANGE");
    private final Label statusLabel = new Label();
    private final TextField folderField = new TextField();
    private final TextField nameField = new TextField();
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
        hint.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        hint.setWrapText(true);

        DropZone dropZone = new DropZone(files -> { if (!files.isEmpty()) loadFile(files.get(0)); });

        statusLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.7;");
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

        // --- output: folder + name ---
        I18n.bindText(folderField::setPromptText, "output.folder.prompt");
        folderField.setText(OutputPaths.defaultDir().toString());
        HBox.setHgrow(folderField, Priority.ALWAYS);
        Button browse = new Button("…");
        browse.getStyleClass().add("btn-secondary");
        browse.setOnAction(e -> browseFolder());
        HBox folderRow = new HBox(6, folderField, browse);
        I18n.bindText(nameField::setPromptText, "output.file.prompt");
        nameField.setText(OutputPaths.DEFAULT_FILE);
        Label folderLabel = new Label();
        I18n.bindText(folderLabel::setText, "output.folder");
        Label nameLabel = new Label();
        I18n.bindText(nameLabel::setText, "output.name");
        VBox outputBox = new VBox(4, folderLabel, folderRow, nameLabel, nameField);

        progressPanel.getRunButton().setOnAction(e -> onRun());

        VBox bottom = new VBox(14, outputBox, progressPanel);

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
            String stem = stripExt(loadedName);
            nameField.setText(stem + "_arranged.pdf");
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
        Path output = resolveOutput();
        Task<ArrangeResult> task = new Task<>() {
            @Override
            protected ArrangeResult call() throws Exception {
                updateMessage(I18n.t("arrange.saving"));
                return PdfArranger.execute(new ArrangeOptions(source, order, output));
            }
        };
        progressPanel.run(task, output);
    }

    // --- output helpers ---------------------------------------------------

    private Path resolveOutput() {
        String folder = folderField.getText();
        Path dir = (folder == null || folder.isBlank())
            ? OutputPaths.defaultDir() : Path.of(folder.strip());
        String name = nameField.getText();
        if (name == null || name.isBlank()) name = OutputPaths.DEFAULT_FILE;
        name = name.strip();
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
        return dir.resolve(name);
    }

    private void browseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("chooser.selectfolder"));
        try {
            Path p = Path.of(folderField.getText());
            if (Files.isDirectory(p)) chooser.setInitialDirectory(p.toFile());
        } catch (Exception ignored) {}
        var dir = chooser.showDialog((Stage) getScene().getWindow());
        if (dir != null) folderField.setText(dir.getAbsolutePath());
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
