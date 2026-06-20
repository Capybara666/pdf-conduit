package org.example.app.gui.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressPanelMessageTest {

    @Test
    void usesExceptionMessageWhenPresent() {
        assertEquals("disk full", ProgressPanel.messageOf(new RuntimeException("disk full")));
    }

    @Test
    void fallsBackToTypeWhenMessageIsNull() {
        assertEquals("NullPointerException", ProgressPanel.messageOf(new NullPointerException()));
    }

    @Test
    void handlesNullThrowable() {
        assertEquals("Operation failed.", ProgressPanel.messageOf(null));
    }
}
