package com.pdfconduit.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Inputs for {@link com.pdfconduit.core.operations.PdfSigner} (Fill &amp; Sign, Phase 1):
 * the source PDF, the signature images to stamp, where to stamp each (see
 * {@link SignPlacement}), optional AcroForm field values to fill (field fully-qualified
 * name → value; text fields and checkboxes), whether to flatten the form afterwards so
 * its fields become static content, and where to write.
 *
 * <p>All lists/maps may be empty: a request may place only signatures, only fill fields,
 * or both. Flattening applies to any AcroForm present regardless of whether fields were
 * filled.
 *
 * @param images      signature images (referenced by {@link SignPlacement#imageIndex()})
 * @param fieldValues AcroForm field values by fully-qualified name; {@code null}/empty = none
 * @param flatten     when true, flatten the AcroForm so fields render as fixed page content
 */
public record SignOptions(Path input, List<Path> images, List<SignPlacement> placements,
                          Map<String, String> fieldValues, boolean flatten, Path output) {}
