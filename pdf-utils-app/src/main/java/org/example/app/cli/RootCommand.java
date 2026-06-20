package org.example.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "pdf-conduit",
    mixinStandardHelpOptions = true,
    version = "PDF Conduit 1.0.0",
    description = "PDF manipulation utility — merge, split, compress, rotate, convert images.",
    subcommands = {
        MergeCommand.class,
        SplitCommand.class,
        CompressCommand.class,
        RotateCommand.class,
        ArrangeCommand.class,
        ImagesToPdfCommand.class,
        ProtectCommand.class,
        UnlockCommand.class,
        MetadataCommand.class,
        WatermarkCommand.class,
        PipelineCommand.class,
        CommandLine.HelpCommand.class
    }
)
public class RootCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
