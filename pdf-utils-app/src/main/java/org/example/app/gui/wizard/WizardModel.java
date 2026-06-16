package org.example.app.gui.wizard;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.example.core.model.PageSize;
import org.example.core.model.PageSource;

public class WizardModel {
    public final ObservableList<PageSource> pages = FXCollections.observableArrayList();
    public final ObjectProperty<PageSize> globalPageSize = new SimpleObjectProperty<>(PageSize.FIT);
    public final BooleanProperty compress = new SimpleBooleanProperty(false);
    public final LongProperty targetSizeBytes = new SimpleLongProperty(5L * 1024 * 1024);
    public final StringProperty outputPath = new SimpleStringProperty("");
}
