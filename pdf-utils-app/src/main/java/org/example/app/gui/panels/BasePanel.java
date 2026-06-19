package org.example.app.gui.panels;

import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.example.app.gui.component.DropZone;
import org.example.app.gui.component.FileListView;
import org.example.app.gui.component.ProgressPanel;
import org.example.app.gui.util.OutputPaths;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PageSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared layout for every operation panel: a header (title, drop zone, file
 * toolbar) pinned to the top, the controls (options, output, run) pinned to the
 * bottom, and the file list filling all the space in between.
 *
 * <p>Batch-capable operations (see {@link #supportsBatch()}) switch the output
 * target from a single file to a folder when more than one file is selected,
 * producing one output per input. The output row itself never changes shape —
 * only its label, prompt and browse dialog adapt — so the view stays stable.
 */
public abstract class BasePanel extends BorderPane {

    protected final FileListView fileList;
    protected final DropZone dropZone;
    protected final ProgressPanel progressPanel;

    // Output is always a folder plus — for single-output runs — a file name.
    private final TextField folderField = new TextField();
    private final TextField nameField = new TextField();
    private final Label folderLabel = new Label(I18n.t("output.folder"));
    private final Label nameLabel = new Label(I18n.t("output.name"));

    private final String outputSuffix;
    private boolean batchMode = false;
    private String lastAutoFolder = "";
    private String lastAutoName = "";

    protected BasePanel(String title, String runLabel, String outputSuffix) {
        this.outputSuffix = outputSuffix;
        getStyleClass().add("panel-root");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("panel-title");

        fileList = new FileListView();
        dropZone = new DropZone(fileList::addFiles);

        // --- file list toolbar: count + clear ---
        Label countLabel = new Label(I18n.t("files.none"));
        countLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
        Button clearBtn = new Button(I18n.t("btn.clear"));
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setDisable(true);
        clearBtn.setOnAction(e -> fileList.getFiles().clear());
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox listToolbar = new HBox(8, countLabel, toolbarSpacer, clearBtn);
        listToolbar.setStyle("-fx-alignment: CENTER_LEFT;");

        // --- output: a folder, plus a file name when there is a single output ---
        folderField.setPromptText(I18n.t("output.folder.prompt"));
        folderField.setText(OutputPaths.defaultDir().toString());
        lastAutoFolder = folderField.getText();
        HBox.setHgrow(folderField, Priority.ALWAYS);
        Button browseBtn = new Button("…");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> browseFolder());
        HBox folderRow = new HBox(6, folderField, browseBtn);

        nameField.setPromptText(I18n.t("output.file.prompt"));
        nameField.setText(OutputPaths.DEFAULT_FILE);
        lastAutoName = nameField.getText();
        HBox.setHgrow(nameField, Priority.ALWAYS);
        // Name label + field only show for single-output runs.
        nameLabel.managedProperty().bind(nameLabel.visibleProperty());
        nameField.managedProperty().bind(nameField.visibleProperty());

        VBox outputBox = new VBox(4, folderLabel, folderRow, nameLabel, nameField);

        progressPanel = new ProgressPanel(runLabel);
        progressPanel.getRunButton().setOnAction(e -> onRun());

        // React to file-list changes: count, clear button, output mode + auto path.
        fileList.getFiles().addListener((ListChangeListener<Path>) change -> {
            int n = fileList.getFiles().size();
            countLabel.setText(n == 0 ? I18n.t("files.none") : I18n.t("files.count", n));
            clearBtn.setDisable(n == 0);
            refreshOutputMode();
        });

        // --- assemble: top pinned, list fills, bottom pinned ---
        VBox top = new VBox(14);
        top.getChildren().add(titleLabel);
        String hint = inputHint();
        if (hint != null) {
            Label hintLabel = new Label(hint);
            hintLabel.setStyle("-fx-font-size: 11px; -fx-opacity: 0.6;");
            hintLabel.setWrapText(true);
            top.getChildren().add(hintLabel);
        }
        top.getChildren().addAll(dropZone, listToolbar);

        VBox bottom = new VBox(14, buildOptionsArea(), outputBox, progressPanel);

        setTop(top);
        setCenter(fileList);
        setBottom(bottom);
        BorderPane.setMargin(fileList, new Insets(14, 0, 14, 0));
    }

    // --- output mode (file vs folder) -------------------------------------

    private void refreshOutputMode() {
        batchMode = supportsBatch() && fileList.getFiles().size() > 1;
        // Several outputs go to a folder; a single output also gets a file name.
        nameLabel.setVisible(!batchMode);
        nameField.setVisible(!batchMode);
        folderLabel.setText(I18n.t("output.folder"));
        updateAutoOutput();
    }

    /** Auto-fills the file name from the first input, unless the user typed their own. */
    private void updateAutoOutput() {
        if (fileList.getFiles().isEmpty()) return;
        Path first = fileList.getFiles().get(0);
        String auto = stripExt(first.getFileName().toString()) + outputSuffix + ".pdf";
        String current = nameField.getText();
        if (current == null || current.isBlank() || current.equals(lastAutoName)) {
            nameField.setText(auto);
            lastAutoName = auto;
        }
    }

    private void browseFolder() {
        Stage stage = (Stage) getScene().getWindow();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("chooser.selectfolder"));
        initialDir(folderField.getText()).ifPresent(chooser::setInitialDirectory);
        var dir = chooser.showDialog(stage);
        if (dir != null) folderField.setText(dir.getAbsolutePath());
    }

    /** An existing directory to start a chooser in: the given path, else the default output dir. */
    private java.util.Optional<java.io.File> initialDir(String pathText) {
        try {
            if (pathText != null && !pathText.isBlank()) {
                Path p = Path.of(pathText);
                if (Files.isDirectory(p)) return java.util.Optional.of(p.toFile());
            }
        } catch (Exception ignored) {}
        Path def = OutputPaths.defaultDir();
        return Files.isDirectory(def) ? java.util.Optional.of(def.toFile()) : java.util.Optional.empty();
    }

    // --- extension points -------------------------------------------------

    protected abstract VBox buildOptionsArea();

    protected abstract void onRun();

    /** Optional hint shown under the title. */
    protected String inputHint() { return null; }

    /** Whether this operation processes each input independently (one output per input). */
    protected boolean supportsBatch() { return false; }

    /** True when several files are selected and this operation runs per-file. */
    protected final boolean isBatchMode() { return batchMode; }

    /** The chosen output folder (for batch / per-file runs). */
    protected final Path outputDir() {
        String folder = folderField.getText();
        return (folder == null || folder.isBlank()) ? OutputPaths.defaultDir() : Path.of(folder.strip());
    }

    /**
     * The single-output destination: the chosen folder + file name. The name
     * defaults to {@code defaultPath}'s file name when left blank, and always
     * ends in {@code .pdf}.
     */
    protected Path resolveOutput(Path defaultPath) {
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            name = defaultPath.getFileName() != null
                ? defaultPath.getFileName().toString() : OutputPaths.DEFAULT_FILE;
        }
        name = name.strip();
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
        return outputDir().resolve(name);
    }

    /**
     * Runs {@code op} for every selected file, naming each result
     * {@code <stem><suffix>.pdf} inside the chosen output folder. Used by
     * batch-capable panels when {@link #isBatchMode()} is true.
     */
    protected void runPerFile(String verb, FileOp op) {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        Path dir = outputDir();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                Files.createDirectories(dir);
                for (int i = 0; i < files.size(); i++) {
                    Path in = files.get(i);
                    updateMessage(verb + " " + (i + 1) + "/" + files.size() + "…");
                    Path out = dir.resolve(
                        stripExt(in.getFileName().toString()) + outputSuffix + ".pdf");
                    List<Path> temps = new ArrayList<>();
                    try {
                        // Non-PDF inputs (images, documents) are converted first.
                        Path pdfIn = DocumentConverter.ensurePdf(in, PageSize.FIT, temps);
                        op.apply(pdfIn, out);
                    } catch (Exception ex) {
                        throw new Exception(in.getFileName() + ": " + ex.getMessage(), ex);
                    } finally {
                        for (Path t : temps) Files.deleteIfExists(t);
                    }
                    updateProgress(i + 1, files.size());
                }
                return dir;
            }
        };
        progressPanel.run(task, dir);
    }

    @FunctionalInterface
    protected interface FileOp {
        void apply(Path input, Path output) throws Exception;
    }

    protected static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
