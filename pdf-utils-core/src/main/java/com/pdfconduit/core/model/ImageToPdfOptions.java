package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;

public record ImageToPdfOptions(List<Path> images, PageSize pageSize, Path output) {
    public ImageToPdfOptions {
        images = List.copyOf(images);
    }
}
