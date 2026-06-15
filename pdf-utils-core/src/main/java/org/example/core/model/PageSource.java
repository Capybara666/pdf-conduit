package org.example.core.model;

import java.nio.file.Path;

public sealed interface PageSource permits PdfPageSource, ImageSource {}

record PdfPageSource(Path file, PageRange range) implements PageSource {}

record ImageSource(Path file, PageSize targetSize) implements PageSource {}
