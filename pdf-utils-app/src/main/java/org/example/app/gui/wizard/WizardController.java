package org.example.app.gui.wizard;

import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

import java.util.List;

public class WizardController extends BorderPane {

    private static final List<String> STEP_NAMES =
        List.of("Files", "Arrange", "Settings", "Compress", "Export");

    private final WizardModel model = new WizardModel();
    private final List<WizardStep> steps;
    private int currentStep = 0;

    private final VBox stepIndicator = new VBox();
    private final StackPane stepContent = new StackPane();
    private final Button backBtn = new Button("← Back");
    private final Button nextBtn = new Button("Next →");

    public WizardController() {
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
        rebuildStepIndicator(0);

        backBtn.getStyleClass().add("btn-secondary");
        nextBtn.getStyleClass().add("btn-primary");
        backBtn.setOnAction(e -> navigate(-1));
        nextBtn.setOnAction(e -> navigate(+1));

        HBox footer = new HBox(8, backBtn, nextBtn);
        footer.getStyleClass().add("wizard-footer");

        setTop(stepIndicator);
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
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setVgap(4);

        for (int i = 0; i < STEP_NAMES.size(); i++) {
            int col = i * 2;

            Label circle = new Label(i < current ? "✓" : String.valueOf(i + 1));
            circle.getStyleClass().addAll("wizard-step-circle",
                i < current ? "done" : (i == current ? "current" : "pending"));
            grid.add(circle, col, 0);
            GridPane.setHalignment(circle, HPos.CENTER);
            GridPane.setValignment(circle, VPos.CENTER);

            Label name = new Label(STEP_NAMES.get(i));
            name.getStyleClass().add("wizard-step-label");
            grid.add(name, col, 1);
            GridPane.setHalignment(name, HPos.CENTER);

            if (i < STEP_NAMES.size() - 1) {
                Region connector = new Region();
                connector.getStyleClass().add("wizard-step-connector");
                grid.add(connector, col + 1, 0);
                GridPane.setValignment(connector, VPos.CENTER);
            }
        }

        stepIndicator.getChildren().add(grid);
    }
}
