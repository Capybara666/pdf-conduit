package com.pdfconduit.app.gui.wizard;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import com.pdfconduit.core.model.PageSize;
import com.pdfconduit.core.model.PageSource;

public class WizardModel {
    public final ObservableList<PageSource> pages = FXCollections.observableArrayList();
    public final ObjectProperty<PageSize> globalPageSize = new SimpleObjectProperty<>(PageSize.FIT);
    public final BooleanProperty compress = new SimpleBooleanProperty(false);
    public final LongProperty targetSizeBytes = new SimpleLongProperty(5L * 1024 * 1024);
    public final StringProperty outputPath = new SimpleStringProperty("");
}
