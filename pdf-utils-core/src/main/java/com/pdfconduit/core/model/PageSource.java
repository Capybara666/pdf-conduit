package com.pdfconduit.core.model;

import java.nio.file.Path;

public sealed interface PageSource permits PageSource.PdfPageSource, PageSource.ImageSource {

    record PdfPageSource(Path file, PageRange range) implements PageSource {}

    record ImageSource(Path file, PageSize targetSize) implements PageSource {}
}
