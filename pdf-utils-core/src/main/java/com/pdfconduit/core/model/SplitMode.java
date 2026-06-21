package com.pdfconduit.core.model;

/**
 * How a page selection is written out. Extract and "split into separate files"
 * are the same selection operation — they differ only here: whether the chosen
 * pages are combined into one PDF or saved one file per page.
 */
public enum SplitMode {
    /** All selected pages go into a single PDF ({@code output} is a file). */
    COMBINE,
    /** Each selected page becomes its own PDF ({@code output} is a folder). */
    SEPARATE
}
