package org.example.app.gui.panels;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.app.gui.Ui;
import org.example.app.i18n.I18n;
import org.example.core.convert.DocumentConverter;
import org.example.core.model.PdfResult;
import org.example.core.service.OperationRunner;
import org.example.core.service.OperationType;
import org.example.core.model.WatermarkOptions;
import org.example.core.operations.PdfWatermarker;

import java.nio.file.Path;
import java.util.List;

/** Stamp a text or image watermark onto every page. */
public class WatermarkPanel extends BasePanel {

    private RadioButton textMode;
    private TextField textField;
    private TextField imageField;
    private Slider opacity;
    private Slider size;
    private ComboBox<Integer> rotation;

    public WatermarkPanel() { super("panel.WATERMARK.title", "run.WATERMARK", OperationType.WATERMARK); }

    @Override
    protected boolean supportsBatch() { return true; }

    @Override
    protected String inputHintKey() { return "hint.WATERMARK"; }

    @Override
    protected VBox buildOptionsArea() {
        ToggleGroup mode = new ToggleGroup();
        textMode = new RadioButton();
        I18n.bindText(textMode::setText, "watermark.mode.text");
        textMode.setToggleGroup(mode);
        textMode.setSelected(true);
        RadioButton imageMode = new RadioButton();
        I18n.bindText(imageMode::setText, "watermark.mode.image");
        imageMode.setToggleGroup(mode);
        HBox modeRow = new HBox(Ui.INLINE_GAP, textMode, imageMode);

        textField = new TextField();
        I18n.bindText(textField::setPromptText, "watermark.text.prompt");
        VBox textRow = labeledField("watermark.text.label", textField);
        textRow.visibleProperty().bind(textMode.selectedProperty());
        textRow.managedProperty().bind(textRow.visibleProperty());

        imageField = new TextField();
        HBox.setHgrow(imageField, Priority.ALWAYS);
        Button browse = new Button("…");
        browse.getStyleClass().add("btn-secondary");
        browse.setOnAction(e -> browseImage());
        VBox imageRow = labeledField("watermark.image.label", new HBox(Ui.INLINE_GAP, imageField, browse));
        imageRow.visibleProperty().bind(imageMode.selectedProperty());
        imageRow.managedProperty().bind(imageRow.visibleProperty());

        opacity = new Slider(0.05, 1.0, 0.3);
        opacity.setPrefWidth(160);
        Label opacityValue = new Label();
        opacity.valueProperty().addListener((o, a, b) ->
            opacityValue.setText(Math.round(b.doubleValue() * 100) + "%"));
        opacityValue.setText("30%");
        HBox opacityRow = new HBox(Ui.INLINE_GAP, fieldLabel("watermark.opacity.label"), opacity, opacityValue);
        opacityRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        size = new Slider(0.1, 2.0, 0.7);
        size.setPrefWidth(160);
        Label sizeValue = new Label();
        size.valueProperty().addListener((o, a, b) ->
            sizeValue.setText(Math.round(b.doubleValue() * 100) + "%"));
        sizeValue.setText("70%");
        HBox sizeRow = new HBox(Ui.INLINE_GAP, fieldLabel("watermark.size.label"), size, sizeValue);
        sizeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        rotation = new ComboBox<>(FXCollections.observableArrayList(0, 45, 90));
        rotation.setValue(45);
        HBox rotationRow = new HBox(Ui.INLINE_GAP, fieldLabel("watermark.rotation.label"), rotation);
        rotationRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        return new VBox(Ui.OPTION_GAP, modeRow, textRow, imageRow, sizeRow, opacityRow, rotationRow);
    }

    private void browseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("watermark.image.label"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
            I18n.t("filter.images"), DocumentConverter.IMAGE_GLOBS.toArray(String[]::new)));
        var f = chooser.showOpenDialog((Stage) getScene().getWindow());
        if (f != null) imageField.setText(f.getAbsolutePath());
    }

    @Override
    protected void onRun() {
        List<Path> files = List.copyOf(fileList.getFiles());
        if (files.isEmpty()) return;
        boolean useText = textMode.isSelected();
        String text = useText ? textField.getText() : null;
        String imagePath = imageField.getText();
        Path image = (!useText && imagePath != null && !imagePath.isBlank()) ? Path.of(imagePath) : null;
        double op = opacity.getValue();
        double sc = size.getValue();
        double rot = rotation.getValue() == null ? 45 : rotation.getValue();

        if (isBatchMode()) {
            runPerFile("verb.watermark", (in, out) ->
                PdfWatermarker.execute(new WatermarkOptions(in, text, image, op, rot, sc, out)));
            return;
        }

        Path input = files.get(0);
        Path output = resolveOutputFor(input);
        Task<PdfResult> task = new Task<>() {
            @Override
            protected PdfResult call() throws Exception {
                updateMessage(I18n.t("msg.busy", I18n.t("verb.watermark")));
                return OperationRunner.run(input, output,
                    (pdf, out) -> PdfWatermarker.execute(new WatermarkOptions(pdf, text, image, op, rot, sc, out)));
            }
        };
        progressPanel.run(task, output);
    }
}
