package com.pdfconduit.app.gui.panels;

import javafx.collections.ListChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import com.pdfconduit.app.gui.Ui;
import com.pdfconduit.app.gui.component.DropZone;
import com.pdfconduit.app.gui.component.FileListView;
import com.pdfconduit.app.gui.component.Forms;
import com.pdfconduit.app.gui.component.OutputPathControl;
import com.pdfconduit.app.gui.component.ProgressPanel;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.service.OperationRunner;
import com.pdfconduit.core.service.OperationType;

import java.nio.file.Path;
import java.util.List;

/**
 * Shared layout for every operation panel: a header (title, optional hint, drop
 * zone, file toolbar) pinned to the top, the controls (options, output, run)
 * pinned to the bottom, and the file list filling all the space in between.
 *
 * <p>Batch-capable operations (see {@link #supportsBatch()}) switch the output
 * target from a single file to a folder when more than one file is selected,
 * producing one output per input. The output row itself never changes shape —
 * only the file-name field shows or hides — so the view stays stable.
 */
public abstract class BasePanel extends BorderPane {

    protected final FileListView fileList;
    protected final DropZone dropZone;
    protected final ProgressPanel progressPanel;
    protected final OutputPathControl output;

    private final OperationType operationType;
    private boolean batchMode = false;

    protected BasePanel(String titleKey, String runKey, OperationType operationType) {
        this.operationType = operationType;
        getStyleClass().add("panel-root");

        Label titleLabel = new Label();
        titleLabel.getStyleClass().add("panel-title");
        I18n.bindText(titleLabel::setText, titleKey);

        fileList = new FileListView();
        dropZone = new DropZone(fileList::addFiles);

        // --- file list toolbar: count + clear ---
        Label countLabel = new Label();
        countLabel.getStyleClass().add("text-caption");
        // The count is computed from live state, so it can't be a static bindText;
        // recompute it on file-list changes and on every language change.
        Runnable refreshCount = () -> {
            int n = fileList.getFiles().size();
            countLabel.setText(n == 0 ? I18n.t("files.none") : I18n.t("files.count", n));
        };
        refreshCount.run();
        I18n.addListener(refreshCount);
        Button clearBtn = new Button();
        I18n.bindText(clearBtn::setText, "btn.clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setDisable(true);
        clearBtn.setOnAction(e -> fileList.getFiles().clear());
        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);
        HBox listToolbar = new HBox(Ui.INLINE_GAP, countLabel, toolbarSpacer, clearBtn);
        listToolbar.getStyleClass().add("row-left");

        output = new OutputPathControl();

        progressPanel = new ProgressPanel(runKey);
        progressPanel.getRunButton().setOnAction(e -> onRun());
        // Nothing to run until at least one file is added.
        progressPanel.getRunButton().setDisable(true);

        // React to file-list changes: count, clear button, run button, output mode + auto path.
        fileList.getFiles().addListener((ListChangeListener<Path>) change -> {
            refreshCount.run();
            boolean empty = fileList.getFiles().isEmpty();
            clearBtn.setDisable(empty);
            progressPanel.getRunButton().setDisable(empty);
            refreshOutputMode();
        });

        // --- assemble: top pinned, list fills, bottom pinned ---
        VBox top = new VBox(14);
        top.getChildren().add(titleLabel);
        String hintKey = inputHintKey();
        if (hintKey != null) {
            Label hintLabel = new Label();
            I18n.bindText(hintLabel::setText, hintKey);
            hintLabel.getStyleClass().add("text-caption");
            hintLabel.setWrapText(true);
            top.getChildren().add(hintLabel);
        }
        top.getChildren().addAll(dropZone, listToolbar);

        VBox bottom = new VBox(14, buildOptionsArea(), output, progressPanel);

        setTop(top);
        setCenter(fileList);
        setBottom(bottom);
        BorderPane.setMargin(fileList, new Insets(14, 0, 14, 0));
        refreshOutputMode();
    }

    // --- output mode (file vs folder) -------------------------------------

    protected void refreshOutputMode() {
        batchMode = (supportsBatch() && fileList.getFiles().size() > 1) || folderOnly();
        // Several outputs go to a folder; a single output also gets a file name.
        output.setSingleOutput(!batchMode);
        if (!fileList.getFiles().isEmpty()) {
            Path first = fileList.getFiles().get(0);
            output.suggestName(stripExt(first.getFileName().toString())
                + operationType.suffix() + ".pdf");
        }
    }

    /**
     * When true, output is always a folder (no single file name), regardless of how
     * many files are selected. Panels whose run can produce several files from a
     * single input (e.g. splitting into separate files) override this.
     */
    protected boolean folderOnly() { return false; }

    // --- extension points -------------------------------------------------

    protected abstract VBox buildOptionsArea();

    protected abstract void onRun();

    /** Message key for an optional hint shown under the title; {@code null} for none. */
    protected String inputHintKey() { return null; }

    /** Whether this operation processes each input independently (one output per input). */
    protected boolean supportsBatch() { return false; }

    /** The catalog operation this panel runs. */
    protected final OperationType operationType() { return operationType; }

    /** True when several files are selected and this operation runs per-file. */
    protected final boolean isBatchMode() { return batchMode; }

    /** The chosen output folder (for batch / per-file runs). */
    protected final Path outputDir() { return output.outputDir(); }

    /**
     * The single-output destination for {@code input}: the chosen folder + name,
     * defaulting to {@code <stem><suffix>.pdf} from the operation catalog when the
     * name field is blank.
     */
    protected final Path resolveOutputFor(Path input) {
        String name = stripExt(input.getFileName().toString()) + operationType.suffix() + ".pdf";
        return output.resolveOutput(name);
    }

    /**
     * Like {@link #resolveOutputFor(Path)} but guards against clobbering: if the
     * destination already exists the user is asked to overwrite, save under a unique
     * name, or cancel. Returns the path to write to, or empty when cancelled.
     */
    protected final java.util.Optional<Path> confirmOutputFor(Path input) {
        return com.pdfconduit.app.gui.util.OutputGuard.confirm(this, resolveOutputFor(input));
    }

    // --- shared option-control helpers ------------------------------------

    /** A standard option label (small caption styling). */
    protected Label fieldLabel(String key) {
        return Forms.label(key);
    }

    /** A label stacked above the field it describes, with the standard gap. */
    protected VBox labeledField(String key, Node field) {
        return Forms.labeledField(key, field);
    }

    // --- batch plumbing ---------------------------------------------------

    /**
     * Runs {@code op} for every selected file, naming each result
     * {@code <stem><suffix>.pdf} inside the chosen output folder. Used by
     * batch-capable panels when {@link #isBatchMode()} is true. {@code verbKey} is
     * an i18n key for the gerund shown in the progress message (e.g. {@code verb.compress}).
     */
    protected void runPerFile(String verbKey, FileOp op) {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        Path dir = outputDir();
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t(verbKey)));
                return OperationRunner.runBatch(operationType(), files, dir,
                    (in, out) -> { op.apply(in, out); return null; },
                    (completed, total) -> {
                        updateMessage(I18n.t("msg.busy.count", I18n.t(verbKey), completed, total));
                        updateProgress(completed, total);
                    },
                    this::isCancelled);
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
