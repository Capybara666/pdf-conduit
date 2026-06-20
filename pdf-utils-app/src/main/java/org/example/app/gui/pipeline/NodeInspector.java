package org.example.app.gui.pipeline;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.util.DefaultLocations;
import org.example.app.pipeline.NodeKind;
import org.example.app.pipeline.PipelineGraph;
import org.example.app.pipeline.PipelineNode;
import org.example.app.i18n.I18n;
import org.example.core.model.PageSize;

import java.io.File;
import java.nio.file.Path;

/** Compact, horizontal options panel for the selected node — lives in the bottom bar. */
class NodeInspector extends HBox {

    private final PipelineCanvas canvas;

    NodeInspector(PipelineCanvas canvas) {
        this.canvas = canvas;
        getStyleClass().add("pipeline-inspector");
        setSpacing(10);
        setAlignment(Pos.CENTER_LEFT);
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
            case MERGE -> getChildren().add(hint(I18n.t("pipeline.merge.hint")));
        }

        if (canvas.model.isTerminal(node)) {
            getChildren().add(new Separator(javafx.geometry.Orientation.VERTICAL));
            buildDestination(node);
        }

        // Keep labels/buttons at full width so the bottom bar never ellipsizes them.
        for (javafx.scene.Node c : getChildren()) {
            if (c instanceof Label || c instanceof Button) ((Region) c).setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    // --- per-kind controls (all laid out horizontally) --------------------

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
                org.example.core.convert.DocumentConverter.ALL_GLOBS.toArray(String[]::new)));
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
        getChildren().addAll(list, buttons);
    }

    private void buildPages(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText(I18n.t("pipeline.node.pages.prompt"));
        pages.setPrefWidth(150);
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });

        ComboBox<org.example.core.model.SplitMode> mode =
            new ComboBox<>(FXCollections.observableArrayList(
                org.example.core.model.SplitMode.COMBINE, org.example.core.model.SplitMode.SEPARATE));
        mode.setValue(node.splitMode);
        mode.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(org.example.core.model.SplitMode m) {
                return I18n.t(m == org.example.core.model.SplitMode.SEPARATE
                    ? "pipeline.node.separate" : "pipeline.node.combine");
            }
            @Override public org.example.core.model.SplitMode fromString(String s) { return null; }
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

        getChildren().addAll(new Label(I18n.t("pipeline.node.pages")), pages,
            new Label(I18n.t("pipeline.node.output")), mode);
    }

    private void buildRotate(PipelineNode node) {
        TextField pages = new TextField(node.pages);
        pages.setPromptText(I18n.t("pipeline.node.pages.prompt"));
        pages.setPrefWidth(150);
        pages.textProperty().addListener((o, a, b) -> { node.pages = b; canvas.refreshNode(node); });
        ComboBox<Integer> angle = new ComboBox<>(FXCollections.observableArrayList(90, 180, 270));
        angle.setValue(node.angle);
        angle.valueProperty().addListener((o, a, b) -> { if (b != null) { node.angle = b; canvas.refreshNode(node); } });
        getChildren().addAll(new Label(I18n.t("pipeline.node.pages")), pages,
            new Label(I18n.t("pipeline.node.angle")), angle);
    }

    private void buildArrange(PipelineNode node) {
        TextField order = new TextField(node.order);
        order.setPromptText(I18n.t("pipeline.node.order.prompt"));
        order.setPrefWidth(180);
        order.textProperty().addListener((o, a, b) -> { node.order = b; canvas.refreshNode(node); });
        getChildren().addAll(new Label(I18n.t("pipeline.node.order")), order);
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
        getChildren().addAll(new Label(I18n.t("pipeline.node.target")), size, unit);
    }

    private void buildImages(PipelineNode node) {
        ComboBox<PageSize> box = new ComboBox<>(FXCollections.observableArrayList(PageSize.values()));
        box.setValue(node.pageSize);
        box.valueProperty().addListener((o, a, b) -> { if (b != null) { node.pageSize = b; canvas.refreshNode(node); } });
        getChildren().addAll(new Label(I18n.t("pipeline.node.pagesize")), box);
    }

    private void buildProtect(PipelineNode node) {
        javafx.scene.control.PasswordField pwd = new javafx.scene.control.PasswordField();
        pwd.setText(node.password);
        pwd.setPromptText(I18n.t("pipeline.node.password.prompt"));
        pwd.setPrefWidth(140);
        pwd.textProperty().addListener((o, a, b) -> { node.password = b; canvas.refreshNode(node); });

        javafx.scene.control.PasswordField owner = new javafx.scene.control.PasswordField();
        owner.setText(node.ownerPassword);
        owner.setPrefWidth(140);
        owner.textProperty().addListener((o, a, b) -> { node.ownerPassword = b; canvas.refreshNode(node); });

        getChildren().addAll(new Label(I18n.t("pipeline.node.password")), pwd,
            new Label(I18n.t("pipeline.node.ownerpassword")), owner);
    }

    private void buildUnlock(PipelineNode node) {
        javafx.scene.control.PasswordField pwd = new javafx.scene.control.PasswordField();
        pwd.setText(node.password);
        pwd.setPromptText(I18n.t("pipeline.node.password.prompt"));
        pwd.setPrefWidth(140);
        pwd.textProperty().addListener((o, a, b) -> { node.password = b; canvas.refreshNode(node); });
        getChildren().addAll(new Label(I18n.t("pipeline.node.password")), pwd);
    }

    private void buildMetadata(PipelineNode node) {
        javafx.scene.control.CheckBox strip = new javafx.scene.control.CheckBox(I18n.t("metadata.strip"));
        strip.setSelected(node.metaStrip);

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
            new Label(I18n.t("metadata.title.label")), title,
            new Label(I18n.t("metadata.author.label")), author,
            new Label(I18n.t("metadata.subject.label")), subject,
            new Label(I18n.t("metadata.keywords.label")), keywords,
            strip);
    }

    private TextField metaField(String value, java.util.function.Consumer<String> setter, PipelineNode node) {
        TextField f = new TextField(value);
        f.setPrefWidth(90);
        f.textProperty().addListener((o, a, b) -> { setter.accept(b); canvas.refreshNode(node); });
        return f;
    }

    private void buildWatermark(PipelineNode node) {
        TextField text = new TextField(node.wmText);
        text.setPromptText(I18n.t("watermark.text.prompt"));
        text.setPrefWidth(120);
        text.textProperty().addListener((o, a, b) -> { node.wmText = b; canvas.refreshNode(node); });

        TextField image = new TextField(node.wmImage);
        image.setPrefWidth(110);
        image.textProperty().addListener((o, a, b) -> { node.wmImage = b; canvas.refreshNode(node); });
        Button browse = secondary("…");
        browse.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18n.t("watermark.image.label"));
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.t("filter.images"),
                org.example.core.convert.DocumentConverter.IMAGE_GLOBS.toArray(String[]::new)));
            File f = fc.showOpenDialog(window());
            if (f != null) image.setText(f.getAbsolutePath());
        });

        Slider opacity = new Slider(0.05, 1.0, node.wmOpacity);
        opacity.setPrefWidth(90);
        opacity.valueProperty().addListener((o, a, b) -> { node.wmOpacity = b.doubleValue(); canvas.refreshNode(node); });

        ComboBox<Integer> rot = new ComboBox<>(FXCollections.observableArrayList(0, 45, 90));
        rot.setValue((int) Math.round(node.wmRotation));
        rot.valueProperty().addListener((o, a, b) -> { if (b != null) { node.wmRotation = b; canvas.refreshNode(node); } });

        getChildren().addAll(
            new Label(I18n.t("watermark.text.label")), text,
            new Label(I18n.t("watermark.image.label")), image, browse,
            new Label(I18n.t("watermark.opacity.label")), opacity,
            new Label(I18n.t("watermark.rotation.label")), rot);
    }

    private void buildDestination(PipelineNode node) {
        // Several inputs each yield a file, and Extract in "separate" mode yields one
        // file per page — both mean the destination is a folder, not a single file.
        boolean separate = node.kind == NodeKind.EXTRACT
            && node.splitMode == org.example.core.model.SplitMode.SEPARATE;
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
            getChildren().addAll(new Label(I18n.t("output.folder")), folder, browse);
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
            getChildren().addAll(new Label(I18n.t("output.folder")), folder, browse,
                                 new Label(I18n.t("output.name")), fileName);
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
