package org.example.app.gui.sidebar;

public enum SidebarItem {
    MERGE    ("⊕", "Merge"),
    SPLIT    ("✂", "Extract"),
    COMPRESS ("⊟", "Compress"),
    ROTATE   ("↻", "Rotate"),
    ARRANGE  ("⇅", "Arrange"),
    IMAGES   ("🖼", "To PDF"),
    PROTECT  ("🔒", "Protect"),
    UNLOCK   ("🔓", "Unlock"),
    METADATA ("🏷", "Metadata"),
    WATERMARK("💧", "Watermark"),
    PIPELINE ("⇄", "Pipeline"),
    WIZARD   ("⚙", "Wizard");

    public final String icon;
    public final String label;

    SidebarItem(String icon, String label) {
        this.icon  = icon;
        this.label = label;
    }
}
