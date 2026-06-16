package org.example.app.gui.wizard;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.util.List;

public class WizardController extends BorderPane {

    private static final List<String> STEP_NAMES =
        List.of("Files", "Arrange", "Settings", "Compress", "Export");

    private final WizardModel model = new WizardModel();
    private final List<WizardStep> steps;
    private final Runnable onExit;
    private int currentStep = 0;

    private final HBox stepIndicator = new HBox();
    private final StackPane stepContent = new StackPane();
    private final Button backBtn = new Button("← Back");
    private final Button nextBtn = new Button("Next →");
    private final Button exitBtn = new Button("✕ Exit Wizard");

    public WizardController(Runnable onExit) {
        this.onExit = onExit;
        steps = List.of(
            new Step1SelectFiles(model),
            new Step2ArrangePages(model),
            new Step3PageSettings(model),
            new Step4Compression(model),
            new Step5Export(model)
        );
        buildLayout();
        showStep(0);
    }

    private void buildLayout() {
        stepIndicator.getStyleClass().add("wizard-step-indicator");
        stepIndicator.setSpacing(0);
        rebuildStepIndicator(0);

        exitBtn.getStyleClass().add("btn-secondary");
        exitBtn.setOnAction(e -> onExit.run());

        HBox header = new HBox();
        HBox.setHgrow(stepIndicator, Priority.ALWAYS);
        header.getChildren().addAll(stepIndicator, exitBtn);
        header.setStyle("-fx-alignment: CENTER; -fx-padding: 8 12 8 12;");

        backBtn.getStyleClass().add("btn-secondary");
        nextBtn.getStyleClass().add("btn-primary");
        backBtn.setOnAction(e -> navigate(-1));
        nextBtn.setOnAction(e -> navigate(+1));

        HBox footer = new HBox(8, backBtn, nextBtn);
        footer.getStyleClass().add("wizard-footer");

        setTop(header);
        setCenter(stepContent);
        setBottom(footer);
    }

    private void navigate(int direction) {
        int next = currentStep + direction;
        if (next >= 0 && next < steps.size()) showStep(next);
    }

    private void showStep(int idx) {
        currentStep = idx;
        stepContent.getChildren().setAll(steps.get(idx).getContent());
        backBtn.setDisable(idx == 0);
        nextBtn.setText(idx == steps.size() - 1 ? "Generate ✓" : "Next →");
        if (idx == steps.size() - 1) {
            nextBtn.setOnAction(e -> steps.get(idx).onFinish());
        } else {
            nextBtn.setOnAction(e -> navigate(+1));
        }
        rebuildStepIndicator(idx);
    }

    private void rebuildStepIndicator(int current) {
        stepIndicator.getChildren().clear();
        for (int i = 0; i < STEP_NAMES.size(); i++) {
            Label circle = new Label(i < current ? "✓" : String.valueOf(i + 1));
            circle.getStyleClass().addAll("wizard-step-circle",
                i < current ? "done" : (i == current ? "current" : "pending"));

            Label name = new Label(STEP_NAMES.get(i));
            name.setStyle("-fx-font-size: 9px;");

            VBox item = new VBox(2, circle, name);
            item.setStyle("-fx-alignment: CENTER;");
            stepIndicator.getChildren().add(item);

            if (i < STEP_NAMES.size() - 1) {
                Region line = new Region();
                line.setStyle("-fx-pref-width: 30; -fx-pref-height: 1; -fx-background-color: #94a3b8;");
                HBox.setMargin(line, new Insets(10, 0, 0, 0));
                stepIndicator.getChildren().add(line);
            }
        }
    }
}
