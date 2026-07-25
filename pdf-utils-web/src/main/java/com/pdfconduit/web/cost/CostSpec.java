package com.pdfconduit.web.cost;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * How one unit of work — an {@code /api} endpoint or a pipeline node kind — turns bytes into heap
 * pressure. This is the <em>declaration</em> half of the cost model: it says how an operation
 * scales, never how much this server can afford (that lives in {@link CostModel}).
 *
 * <p>Every ceiling in the hardening layer bounds one dimension of a request — file size, file
 * count, page count, DPI, pixels per page. None of them bounds a <em>product</em> of those
 * dimensions, which is what actually decides whether a request fits in the heap. A declared spec
 * makes the product computable: given the input bytes (and, for a pipeline, the graph), the cost
 * of the whole request can be estimated <b>before</b> anything is allocated.
 *
 * @param outputFactor  result bytes ÷ input bytes for an operation whose output is proportional to
 *                      its input (rotate ≈ 1.05, to-text ≈ 0.3). Ignored when the spec declares
 *                      {@link Trait#UNBOUNDED_OUTPUT}.
 * @param workingFactor transient heap ÷ input bytes while the operation runs, on top of the bytes
 *                      it retains — the parsed PDFBox document, the compressor's re-encode buffers.
 * @param traits        the qualitative properties {@link CostModel} and the guards key off
 */
public record CostSpec(double outputFactor, double workingFactor, Set<Trait> traits) {

    /**
     * The qualitative ways an operation can cost more than its input suggests. A new endpoint or
     * node kind declares the ones that apply; {@code CostCatalogCompletenessTest} fails if it
     * declares nothing at all.
     */
    public enum Trait {
        /** Renders pages to a raster, so cost scales with pages × DPI², not with input bytes. */
        RASTERISES,
        /** The client can make the output carry more pages than the input (arrange, merge). */
        AMPLIFIES_PAGES,
        /** Shells out to an external binary (LibreOffice, Tesseract) under its own permit. */
        EXTERNAL_PROCESS,
        /** Output size is not a function of input size, so only the request budget bounds it. */
        UNBOUNDED_OUTPUT,
        /** Holds every intermediate stage in the heap at once (the pipeline executor). */
        RETAINS_INTERMEDIATES
    }

    public CostSpec {
        if (outputFactor < 0) outputFactor = 0;
        if (workingFactor < 0) workingFactor = 0;
        traits = traits == null || traits.isEmpty()
            ? Set.of() : Set.copyOf(EnumSet.copyOf(traits));
    }

    public static CostSpec of(double outputFactor, double workingFactor, Trait... traits) {
        return new CostSpec(outputFactor, workingFactor,
            traits.length == 0 ? Set.of() : Set.copyOf(Arrays.asList(traits)));
    }

    /**
     * A unit of work that touches no user data — the catalog endpoints. Declared explicitly rather
     * than left out, so "this costs nothing" is a decision on the record instead of an omission.
     */
    public static final CostSpec NEGLIGIBLE = of(0, 0);

    public boolean has(Trait trait) {
        return traits.contains(trait);
    }

    public boolean rasterises() {
        return has(Trait.RASTERISES);
    }

    public boolean unboundedOutput() {
        return has(Trait.UNBOUNDED_OUTPUT);
    }
}
