package com.pdfconduit.app.gui.pipeline;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import com.pdfconduit.app.gui.util.DefaultLocations;
import com.pdfconduit.core.pipeline.NodeKind;
import com.pdfconduit.core.pipeline.PipelineGraph;
import com.pdfconduit.core.pipeline.PipelineNode;
import com.pdfconduit.app.i18n.I18n;
import com.pdfconduit.core.model.ImageFormat;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.TextFormat;

import java.io.File;
import java.nio.file.Path;

/**
 * Options panel for the selected node — lives in the slim bottom bar. Its
 * controls are grouped into label+field units that <em>wrap</em> onto extra rows
 * when they don't fit, so dense nodes (e.g. Watermark, Metadata) stay readable
 * instead of being squeezed/clipped.
 */
class NodeInspector extends FlowPane {

    private final PipelineCanvas canvas;

    NodeInspector(PipelineCanvas canvas) {
        this.canvas = canvas;
        getStyleClass().add("pipeline-inspector");
        setHgap(12);
        setVgap(8);
        setAlignment(Pos.CENTER_LEFT);
        setRowValignment(VPos.CENTER);
        show(null);
    }

    void show(PipelineNode node) {
        getChildren().clear();
        if (node == null) {
            Label hint = new Label(I18n.t("pipeline.inspector.empty"));
            hint.getStyleClass().add("pipeline-inspector-hint");
            getChildren().add(hint);
            return;
        }

        Label title = new Label(I18n.t("kind." + node.kind.name()));
        title.getStyleClass().add("pipeline-inspector-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().add(title);

        switch (node.kind) {
            case SOURCE -> buildSource(node);
            case EXTRACT -> buildPages(node);
            case ROTATE -> buildRotate(node);
            case ARRANGE -> buildArrange(node);
            case COMPRESS -> buildCompress(node);
            case IMAGES_TO_PDF -> buildImages(node);
            case PROTECT -> buildProtect(node);
            case UNLOCK -> buildUnlock(node);
            case METADATA -> buildMetadata(node);
            case WATERMARK -> buildWatermark(node);
            case CROP -> buildCrop(node);
            case NUP -> buildNup(node);
            case PAGE_MARKS -> buildPageMarks(node);
            case TO_IMAGES -> buildToImages(node);
            case TO_TEXT -> buildToText(node);
            case OCR -> buildOcr(node);
            // No parameters — the node scans for PII and redacts every detected value.
            // TODO(i18n): add key pipeline.gdprredact.hint.
            case GDPR_REDACT -> getChildren().add(hint("Scans for personal data and permanently redacts every detected value."));
            // No parameters — it rebuilds whatever structure survived. Honest about the limits.
            case REPAIR -> getChildren().add(hint(I18n.t("pipeline.repair.hint")));
            case MERGE -> getChildren().add(hint(I18n.t("pipeline.merge.hint")));
        }

        if (canvas.model.isTerminal(node)) {
            getChildren().add(new Separator(javafx.geometry.Orientation.VERTICAL));
            buildDestination(node);
        }
    }

    // --- per-kind controls (each a label+field group that can wrap) -------

    private void buildSource(PipelineNode node) {
        ListView<Path> list = new ListView<>(FXCollections.observableArrayList(node.files));
        list.setPrefHeight(72);
        list.setPrefWidth(260);
        Button add = secondary(I18n.t("btn.addfiles"));
        add.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("chooser.addfiles"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.t("filter.supported"),
                com.pdfconduit.core.convert.DocumentConverter.ALL_GLOBS.toArray(String[]::new)));
            var files = chooser.showOpenMultipleDialog(window());
            if (files != null) {
                for (File f : files) if (!node.files.contains(f.toPath())) node.files.add(f.toPath());
                list.setItems(FXCollections.observableArrayList(node.files));
                canvas.refreshNode(node);
            }
        });
        Button remove = secondary(I18n.t("btn.remove"));
        remove.setOnAction(e -> {
            Path sel = list.getSelectionModel().getSelectedItem();
            if (sel != null) {
                node.files.remove(sel);
                list.setItems(FXCollections.observableArrayList(node.files));
                canvas.refreshNode(node);
            }
        });
        VBox buttons = new VBox(6, add, remove);
        getChildren().add(row(list, buttons));
    }

    private void buildPages(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText(I18n.t("pipeline.node.pages.prompt"));
        pages.setPrefWidth(150);
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });

        ComboBox<com.pdfconduit.core.model.SplitMode> mode =
            new ComboBox<>(FXCollections.observableArrayList(
                com.pdfconduit.core.model.SplitMode.COMBINE, com.pdfconduit.core.model.SplitMode.SEPARATE));
        mode.setValue(node.splitMode);
        mode.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.pdfconduit.core.model.SplitMode m) {
                return I18n.t(m == com.pdfconduit.core.model.SplitMode.SEPARATE
                    ? "pipeline.node.separate" : "pipeline.node.combine");
            }
            @Override public com.pdfconduit.core.model.SplitMode fromString(String s) { return null; }
        });
        // The combo sizes itself from the short enum names, so the (longer)
        // converted labels would render as "…" — pin a width that fits them.
        mode.setPrefWidth(130);
        mode.setMinWidth(Region.USE_PREF_SIZE);
        mode.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                node.splitMode = b;
                canvas.refreshNode(node);
                show(node);          // re-render: the destination switches folder ↔ folder+name
            }
        });

        getChildren().addAll(group("pipeline.node.pages", pages),
            group("pipeline.node.output", mode));
    }

    private void buildRotate(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText(I18n.t("pipeline.node.pages.prompt"));
        pages.setPrefWidth(150);
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });
        ComboBox<Integer> angle = new ComboBox<>(FXCollections.observableArrayList(90, 180, 270));
        angle.setValue(node.angle);
        angle.valueProperty().addListener((o, a, b) -> { if (b != null) { node.angle = b; canvas.refreshNode(node); } });
        getChildren().addAll(group("pipeline.node.pages", pages), group("pipeline.node.angle", angle));
    }

    private void buildArrange(PipelineNode node) {
        TextField order = new TextField(node.order);
        order.setPromptText(I18n.t("pipeline.node.order.prompt"));
        order.setPrefWidth(180);
        order.textProperty().addListener((o, a, b) -> { node.order = b; canvas.refreshNode(node); });
        getChildren().add(group("pipeline.node.order", order));
    }

    private void buildCompress(PipelineNode node) {
        long bytes = node.targetBytes;
        boolean mb = bytes % (1024 * 1024) == 0;
        TextField size = new TextField(String.valueOf(mb ? bytes / (1024 * 1024) : bytes / 1024));
        size.setPrefWidth(70);
        ComboBox<String> unit = new ComboBox<>(FXCollections.observableArrayList("MB", "KB"));
        unit.setValue(mb ? "MB" : "KB");
        Runnable apply = () -> {
            try {
                double v = Double.parseDouble(size.getText().strip());
                node.targetBytes = unit.getValue().equals("MB") ? (long) (v * 1024 * 1024) : (long) (v * 1024);
                canvas.refreshNode(node);
            } catch (NumberFormatException ignored) {}
        };
        size.textProperty().addListener((o, a, b) -> apply.run());
        unit.valueProperty().addListener((o, a, b) -> apply.run());
        getChildren().add(group("pipeline.node.target", size, unit));
    }

    private void buildImages(PipelineNode node) {
        ComboBox<PageSize> box = new ComboBox<>(FXCollections.observableArrayList(PageSize.values()));
        box.setValue(node.pageSize);
        box.valueProperty().addListener((o, a, b) -> { if (b != null) { node.pageSize = b; canvas.refreshNode(node); } });
        getChildren().add(group("pipeline.node.pagesize", box));
    }

    private void buildNup(PipelineNode node) {
        ComboBox<com.pdfconduit.core.model.NupLayout> layout =
            new ComboBox<>(FXCollections.observableArrayList(com.pdfconduit.core.model.NupLayout.values()));
        layout.setValue(node.nupLayout);
        layout.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(com.pdfconduit.core.model.NupLayout l) {
                return l == null ? "" : I18n.t("nup.layout." + l.id());
            }
            @Override public com.pdfconduit.core.model.NupLayout fromString(String s) { return null; }
        });
        layout.setPrefWidth(120);
        layout.setMinWidth(Region.USE_PREF_SIZE);
        layout.valueProperty().addListener((o, a, b) -> { if (b != null) { node.nupLayout = b; canvas.refreshNode(node); } });

        CheckBox booklet = new CheckBox(I18n.t("nup.booklet"));
        booklet.setSelected(node.nupBooklet);
        booklet.setMinWidth(Region.USE_PREF_SIZE);
        layout.disableProperty().bind(booklet.selectedProperty());
        booklet.selectedProperty().addListener((o, a, b) -> { node.nupBooklet = b; canvas.refreshNode(node); });

        getChildren().addAll(group("nup.layout.label", layout), booklet);
    }

    private void buildToImages(PipelineNode node) {
        ComboBox<ImageFormat> fmt = new ComboBox<>(FXCollections.observableArrayList(ImageFormat.values()));
        fmt.setValue(node.imageFormat);
        fmt.valueProperty().addListener((o, a, b) -> { if (b != null) { node.imageFormat = b; canvas.refreshNode(node); } });

        ComboBox<Integer> dpi = new ComboBox<>(FXCollections.observableArrayList(72, 150, 300, 600));
        dpi.setValue(node.imageDpi);
        dpi.valueProperty().addListener((o, a, b) -> { if (b != null) { node.imageDpi = b; canvas.refreshNode(node); } });

        getChildren().addAll(group("pipeline.node.format", fmt), group("pipeline.node.dpi", dpi));
    }

    private void buildToText(PipelineNode node) {
        // Both formats are produced natively (no LibreOffice); always offer TXT and DOCX.
        ComboBox<TextFormat> fmt =
            new ComboBox<>(FXCollections.observableArrayList(TextFormat.values()));
        fmt.setValue(node.textFormat);
        fmt.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(TextFormat t) {
                return t == null ? "" : I18n.t(t == TextFormat.DOCX ? "totext.format.docx" : "totext.format.txt");
            }
            @Override public TextFormat fromString(String s) { return null; }
        });
        fmt.setPrefWidth(130);
        fmt.setMinWidth(Region.USE_PREF_SIZE);
        fmt.valueProperty().addListener((o, a, b) -> { if (b != null) { node.textFormat = b; canvas.refreshNode(node); } });

        getChildren().add(group("pipeline.node.format", fmt));
    }

    private void buildOcr(PipelineNode node) {
        TextField langs = new TextField(node.ocrLanguages);
        langs.setPromptText("eng");
        langs.setPrefWidth(120);
        langs.textProperty().addListener((o, a, b) -> { node.ocrLanguages = b; canvas.refreshNode(node); });

        ComboBox<Integer> dpi = new ComboBox<>(FXCollections.observableArrayList(150, 300, 600));
        dpi.setValue(node.ocrDpi);
        dpi.valueProperty().addListener((o, a, b) -> { if (b != null) { node.ocrDpi = b; canvas.refreshNode(node); } });

        getChildren().addAll(group("pipeline.node.ocrlangs", langs), group("pipeline.node.dpi", dpi));
        if (!com.pdfconduit.core.operations.PdfOcr.available()) {
            getChildren().add(hint(I18n.t("ocr.needstesseract")));
        }
    }

    private void buildProtect(PipelineNode node) {
        PasswordField pwd = new PasswordField();
        pwd.setText(node.password);
        pwd.setPromptText(I18n.t("pipeline.node.password.prompt"));
        pwd.setPrefWidth(140);
        pwd.textProperty().addListener((o, a, b) -> { node.password = b; canvas.refreshNode(node); });

        PasswordField owner = new PasswordField();
        owner.setText(node.ownerPassword);
        owner.setPrefWidth(140);
        owner.textProperty().addListener((o, a, b) -> { node.ownerPassword = b; canvas.refreshNode(node); });

        getChildren().addAll(group("pipeline.node.password", pwd),
            group("pipeline.node.ownerpassword", owner));
    }

    private void buildUnlock(PipelineNode node) {
        PasswordField pwd = new PasswordField();
        pwd.setText(node.password);
        pwd.setPromptText(I18n.t("pipeline.node.password.prompt"));
        pwd.setPrefWidth(140);
        pwd.textProperty().addListener((o, a, b) -> { node.password = b; canvas.refreshNode(node); });
        getChildren().add(group("pipeline.node.password", pwd));
    }

    private void buildMetadata(PipelineNode node) {
        CheckBox strip = new CheckBox(I18n.t("metadata.strip"));
        strip.setSelected(node.metaStrip);
        strip.setMinWidth(Region.USE_PREF_SIZE);

        TextField title = metaField(node.metaTitle, v -> node.metaTitle = v, node);
        TextField author = metaField(node.metaAuthor, v -> node.metaAuthor = v, node);
        TextField subject = metaField(node.metaSubject, v -> node.metaSubject = v, node);
        TextField keywords = metaField(node.metaKeywords, v -> node.metaKeywords = v, node);
        // When stripping, the fields are irrelevant — grey them out.
        for (TextField f : java.util.List.of(title, author, subject, keywords)) {
            f.disableProperty().bind(strip.selectedProperty());
        }
        strip.selectedProperty().addListener((o, a, b) -> { node.metaStrip = b; canvas.refreshNode(node); });

        getChildren().addAll(
            group("metadata.title.label", title),
            group("metadata.author.label", author),
            group("metadata.subject.label", subject),
            group("metadata.keywords.label", keywords),
            strip);
    }

    private TextField metaField(String value, java.util.function.Consumer<String> setter, PipelineNode node) {
        TextField f = new TextField(value);
        f.setPrefWidth(110);
        f.textProperty().addListener((o, a, b) -> { setter.accept(b); canvas.refreshNode(node); });
        return f;
    }

    private void buildWatermark(PipelineNode node) {
        TextField text = new TextField(node.wmText);
        text.setPromptText(I18n.t("watermark.text.prompt"));
        text.setPrefWidth(130);
        text.textProperty().addListener((o, a, b) -> { node.wmText = b; canvas.refreshNode(node); });

        TextField image = new TextField(node.wmImage);
        image.setPrefWidth(130);
        image.textProperty().addListener((o, a, b) -> { node.wmImage = b; canvas.refreshNode(node); });
        Button browse = secondary("…");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.t("watermark.image.label"));
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("filter.images"),
                com.pdfconduit.core.convert.DocumentConverter.IMAGE_GLOBS.toArray(String[]::new)));
            File f = fc.showOpenDialog(window());
            if (f != null) image.setText(f.getAbsolutePath());
        });

        Slider size = new Slider(0.1, 2.0, node.wmScale);
        size.setPrefWidth(110);
        size.valueProperty().addListener((o, a, b) -> { node.wmScale = b.doubleValue(); canvas.refreshNode(node); });

        Slider opacity = new Slider(0.05, 1.0, node.wmOpacity);
        opacity.setPrefWidth(110);
        opacity.valueProperty().addListener((o, a, b) -> { node.wmOpacity = b.doubleValue(); canvas.refreshNode(node); });

        ComboBox<Integer> rot = new ComboBox<>(FXCollections.observableArrayList(0, 45, 90));
        rot.setValue((int) Math.round(node.wmRotation));
        rot.valueProperty().addListener((o, a, b) -> { if (b != null) { node.wmRotation = b; canvas.refreshNode(node); } });

        getChildren().addAll(
            group("watermark.text.label", text),
            group("watermark.image.label", image, browse),
            group("watermark.size.label", size),
            group("watermark.opacity.label", opacity),
            group("watermark.rotation.label", rot));
    }

    private void buildCrop(PipelineNode node) {
        TextField top = marginField(node.cropTop, v -> node.cropTop = v, node);
        TextField right = marginField(node.cropRight, v -> node.cropRight = v, node);
        TextField bottom = marginField(node.cropBottom, v -> node.cropBottom = v, node);
        TextField left = marginField(node.cropLeft, v -> node.cropLeft = v, node);

        ComboBox<String> unit = new ComboBox<>(FXCollections.observableArrayList("pt", "mm"));
        unit.setValue(node.cropMm ? "mm" : "pt");
        unit.valueProperty().addListener((o, a, b) -> {
            if (b != null) { node.cropMm = b.equals("mm"); canvas.refreshNode(node); }
        });

        getChildren().addAll(
            group("pipeline.node.crop.top", top),
            group("pipeline.node.crop.right", right),
            group("pipeline.node.crop.bottom", bottom),
            group("pipeline.node.crop.left", left),
            group("pipeline.node.unit", unit));
    }

    /** A small numeric margin field; non-numeric input is ignored (keeps the last value). */
    private TextField marginField(double value, java.util.function.DoubleConsumer setter, PipelineNode node) {
        TextField f = new TextField(value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value));
        f.setPrefWidth(56);
        f.textProperty().addListener((o, a, b) -> {
            try {
                setter.accept(b == null || b.isBlank() ? 0 : Double.parseDouble(b.trim()));
                canvas.refreshNode(node);
            } catch (NumberFormatException ignored) {}
        });
        return f;
    }

    private void buildPageMarks(PipelineNode node) {
        TextField hc = pmField(node.pmHeaderCenter, v -> node.pmHeaderCenter = v, node);
        TextField fc = pmField(node.pmFooterCenter, v -> node.pmFooterCenter = v, node);

        TextField start = new TextField(String.valueOf(node.pmStartNumber));
        start.setPrefWidth(60);
        start.textProperty().addListener((o, a, b) -> {
            try { node.pmStartNumber = Integer.parseInt(b.strip()); canvas.refreshNode(node); }
            catch (NumberFormatException ignored) {}
        });

        TextField prefix = new TextField(node.pmPrefix);
        prefix.setPromptText(I18n.t("pagemarks.prefix.prompt"));
        prefix.setPrefWidth(110);
        prefix.textProperty().addListener((o, a, b) -> { node.pmPrefix = b; canvas.refreshNode(node); });

        CheckBox skip = new CheckBox(I18n.t("pagemarks.skipfirst"));
        skip.setSelected(node.pmSkipFirst);
        skip.setMinWidth(Region.USE_PREF_SIZE);
        skip.selectedProperty().addListener((o, a, b) -> { node.pmSkipFirst = b; canvas.refreshNode(node); });

        getChildren().addAll(
            group("pagemarks.header", hc),
            group("pagemarks.footer", fc),
            group("pagemarks.start", start),
            group("pagemarks.prefix", prefix),
            skip);
    }

    private TextField pmField(String value, java.util.function.Consumer<String> setter, PipelineNode node) {
        TextField f = new TextField(value);
        f.setPromptText(I18n.t("pagemarks.slot.prompt"));
        f.setPrefWidth(150);
        f.textProperty().addListener((o, a, b) -> { setter.accept(b); canvas.refreshNode(node); });
        return f;
    }

    private void buildDestination(PipelineNode node) {
        // Several inputs each yield a file, and Extract in "separate" mode yields one
        // file per page — both mean the destination is a folder, not a single file.
        boolean separate = node.kind == NodeKind.EXTRACT
            && node.splitMode == com.pdfconduit.core.model.SplitMode.SEPARATE;
        boolean multiple = separate || PipelineGraph.outputCount(canvas.model, node.id) > 1;

        // Normalize the stored destination to the current output multiplicity: a
        // folder for several outputs, a folder+file for a single one. This also
        // fixes a stale single-file default left from before the node was wired
        // to a multi-file source.
        node.outputDestination = normalizeDestination(node.outputDestination, multiple);

        if (multiple) {
            // One file per input -> pick a folder.
            TextField folder = new TextField(node.outputDestination);
            folder.setPromptText(I18n.t("output.folder.prompt"));
            folder.setPrefWidth(260);
            folder.textProperty().addListener((o, a, b) -> node.outputDestination = b);
            Button browse = secondary("…");
            browse.setOnAction(e -> chooseDir(folder));
            getChildren().add(group("output.folder", folder, browse));
        } else {
            // Single output -> folder + a separate file name.
            Path current = Path.of(node.outputDestination);
            String dir = current.getParent() != null ? current.getParent().toString()
                                                      : DefaultLocations.defaultDir().toString();
            String name = current.getFileName() != null ? current.getFileName().toString()
                                                         : DefaultLocations.DEFAULT_FILE;
            TextField folder = new TextField(dir);
            folder.setPrefWidth(220);
            TextField fileName = new TextField(name);
            fileName.setPrefWidth(170);
            Runnable apply = () -> {
                String d = folder.getText() == null ? "" : folder.getText().strip();
                String f = fileName.getText() == null ? "" : fileName.getText().strip();
                if (f.isBlank()) f = DefaultLocations.DEFAULT_FILE;
                if (!f.toLowerCase().endsWith(".pdf")) f = f + ".pdf";
                node.outputDestination = (d.isBlank() ? Path.of(f) : Path.of(d, f)).toString();
            };
            folder.textProperty().addListener((o, a, b) -> apply.run());
            fileName.textProperty().addListener((o, a, b) -> apply.run());
            Button browse = secondary("…");
            browse.setOnAction(e -> chooseDir(folder));
            getChildren().addAll(group("output.folder", folder, browse),
                                 group("output.name", fileName));
        }
    }

    /** Coerces a stored destination to a folder (multiple outputs) or folder+file (single). */
    private static String normalizeDestination(String dest, boolean multiple) {
        boolean blank = dest == null || dest.isBlank();
        boolean looksLikeFile = !blank && dest.toLowerCase().endsWith(".pdf");
        if (multiple) {
            if (blank) return DefaultLocations.defaultDir().toString();
            if (looksLikeFile) {                       // a file path -> use its folder
                Path parent = Path.of(dest).getParent();
                return (parent != null ? parent : DefaultLocations.defaultDir()).toString();
            }
            return dest;                               // already a folder
        }
        if (blank) return DefaultLocations.defaultFile().toString();
        if (looksLikeFile) return dest;                // already folder+file
        return Path.of(dest).resolve(DefaultLocations.DEFAULT_FILE).toString();  // bare folder -> add name
    }

    private void chooseDir(TextField target) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(I18n.t("chooser.selectfolder"));
        try {
            String t = target.getText();
            Path p = (t == null || t.isBlank()) ? DefaultLocations.defaultDir() : Path.of(t);
            if (java.nio.file.Files.isDirectory(p)) dc.setInitialDirectory(p.toFile());
        } catch (Exception ignored) {}
        File dir = dc.showDialog(window());
        if (dir != null) target.setText(dir.getAbsolutePath());
    }

    // --- helpers ----------------------------------------------------------

    /** A label + its control(s) as one unit, so they wrap onto a new row together. */
    private Region group(String labelKey, Node... controls) {
        Label l = new Label(I18n.t(labelKey));
        l.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(6, l);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(controls);
        return box;
    }

    /** Groups controls (no label) into one wrap-as-a-unit row. */
    private Region row(Node... controls) {
        HBox box = new HBox(8, controls);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("pipeline-inspector-hint");
        return l;
    }

    private Button secondary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("btn-secondary");
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private Stage window() {
        return (Stage) getScene().getWindow();
    }
}
