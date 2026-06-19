package org.example.app.gui.sidebar;

public enum SidebarItem {
    MERGE    ("⊕", "Merge"),
    SPLIT    ("✂", "Extract"),
    COMPRESS ("⊟", "Compress"),
    ROTATE   ("↻", "Rotate"),
    ARRANGE  ("⇅", "Arrange"),
    IMAGES   ("🖼", "To PDF"),
    PIPELINE ("⇄", "Pipeline"),
    WIZARD   ("⚙", "Wizard");

    public final String icon;
    public final String label;

    SidebarItem(String icon, String label) {
        this.icon  = icon;
        this.label = label;
    }
}
