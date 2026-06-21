package com.pdfconduit.app;

import com.pdfconduit.app.cli.RootCommand;
import com.pdfconduit.app.gui.GuiLauncher;
import com.pdfconduit.app.gui.util.Settings;
import com.pdfconduit.core.convert.DocumentConverter;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        // Apply a user-configured LibreOffice path (if any) before any conversion runs.
        DocumentConverter.setSofficeOverride(Settings.sofficePath());
        if (args.length > 0) {
            int exitCode = new CommandLine(new RootCommand()).execute(args);
            System.exit(exitCode);
        } else {
            GuiLauncher.launch(args);
        }
    }
}
