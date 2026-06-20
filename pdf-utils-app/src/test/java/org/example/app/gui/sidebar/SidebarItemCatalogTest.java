package org.example.app.gui.sidebar;

import org.example.core.service.OperationType;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.*;

class SidebarItemCatalogTest {

    @Test
    void onlyPipelineAndWizardLackAnOperationType() {
        for (SidebarItem item : SidebarItem.values()) {
            boolean isOp = item != SidebarItem.PIPELINE && item != SidebarItem.WIZARD;
            assertEquals(isOp, item.operationType().isPresent(), item + " operationType presence");
        }
    }

    @Test
    void splitMapsToExtractAndImagesToToPdf() {
        assertEquals(OperationType.EXTRACT, SidebarItem.SPLIT.operationType().orElseThrow());
        assertEquals(OperationType.IMAGES_TO_PDF, SidebarItem.IMAGES.operationType().orElseThrow());
    }

    @Test
    void everyOperationTypeHasABaseTitleAndRunKey() {
        ResourceBundle b = ResourceBundle.getBundle("i18n.messages", Locale.ROOT);
        for (SidebarItem item : SidebarItem.values()) {
            if (item.operationType().isEmpty()) continue;
            assertTrue(b.containsKey("panel." + item.name() + ".title"),
                "missing panel." + item.name() + ".title");
            assertTrue(b.containsKey("run." + item.name()), "missing run." + item.name());
        }
    }
}
