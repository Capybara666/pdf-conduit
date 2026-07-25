package com.pdfconduit.core.service;

/**
 * One input that could not be processed inside an otherwise-successful batch: which file, and why.
 *
 * <p>Collected by {@link MemoryOperations#mapPartial} so a MAP batch can return the results it did
 * produce and still name what it dropped, instead of failing the whole request on the first bad
 * file (and leaving the user to bisect a 15-file upload to find it).
 *
 * @param filename the input's own name, exactly as it was supplied
 * @param message  the user-facing failure message (without the file name prefixed)
 */
public record BatchFailure(String filename, String message) {}
