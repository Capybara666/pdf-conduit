package com.pdfconduit.app.gui.sidebar;

import com.pdfconduit.core.service.OperationType;
import java.util.Optional;

public enum SidebarItem {
    MERGE    ("⊕", "Merge",     OperationType.MERGE),
    SPLIT    ("✂", "Extract",   OperationType.EXTRACT),
    COMPRESS ("⊟", "Compress",  OperationType.COMPRESS),
    ROTATE   ("↻", "Rotate",    OperationType.ROTATE),
    ARRANGE  ("⇅", "Arrange",   OperationType.ARRANGE),
    IMAGES   ("🖼", "To PDF",    OperationType.IMAGES_TO_PDF),
    PROTECT  ("🔒", "Protect",   OperationType.PROTECT),
    UNLOCK   ("🔓", "Unlock",    OperationType.UNLOCK),
    METADATA ("🏷", "Metadata",  OperationType.METADATA),
    WATERMARK("💧", "Watermark", OperationType.WATERMARK),
    PIPELINE ("⇄", "Pipeline",  null),
    WIZARD   ("⚙", "Wizard",    null);

    public final String icon;
    public final String label;
    private final OperationType type;

    SidebarItem(String icon, String label, OperationType type) {
        this.icon  = icon;
        this.label = label;
        this.type  = type;
    }

    /** The catalog operation this item runs, or empty for Pipeline / Wizard. */
    public Optional<OperationType> operationType() { return Optional.ofNullable(type); }
}
