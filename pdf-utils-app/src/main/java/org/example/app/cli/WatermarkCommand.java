package org.example.app.cli;

import org.example.core.exception.PdfOperationException;
import org.example.core.model.PdfResult;
import org.example.core.model.WatermarkOptions;
import org.example.core.operations.PdfWatermarker;
import org.example.core.service.OperationType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "watermark",
         description = "Stamp a text or image watermark onto every page.")
public class WatermarkCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "FILE", description = "Input PDF.")
    private Path input;

    @Option(names = "--text", paramLabel = "TEXT", description = "Watermark text.")
    private String text;

    @Option(names = "--image", paramLabel = "FILE", description = "Watermark image/logo (PNG, JPG).")
    private Path image;

    @Option(names = "--opacity", paramLabel = "0-1", defaultValue = "0.3",
            description = "Opacity, 0–1 (default 0.3).")
    private double opacity;

    @Option(names = "--rotation", paramLabel = "DEG", defaultValue = "45",
            description = "Rotation in degrees (default 45 = diagonal).")
    private double rotation;

    @Option(names = "--scale", paramLabel = "0.05-2", defaultValue = "0.7",
            description = "Size as a fraction of page width (default 0.7 ≈ 70%).")
    private double scale;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Output PDF path.")
    private Path output;

    @Override
    public Integer call() {
        try {
            Path out = output != null ? output : MergeCommand.deriveOutput(input, OperationType.WATERMARK.suffix());
            PdfResult result = PdfWatermarker.execute(
                new WatermarkOptions(input, text, image, opacity, rotation, scale, out));
            System.out.printf("Watermarked %d page(s) → %s%n", result.pageCount(), result.output());
            return 0;
        } catch (PdfOperationException e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
