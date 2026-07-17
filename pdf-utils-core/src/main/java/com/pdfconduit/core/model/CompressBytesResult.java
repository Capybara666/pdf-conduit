package com.pdfconduit.core.model;

/**
 * In-memory analog of {@link CompressResult}: the compressed PDF's bytes plus the same
 * size/target semantics, so a web layer can emit {@code X-Original-Bytes},
 * {@code X-Result-Bytes} and {@code X-Target-Reached}.
 *
 * @param bytes         the resulting PDF (never larger than the input)
 * @param originalBytes size of the input, in bytes
 * @param resultBytes   size of {@link #bytes}, in bytes
 * @param targetReached whether the requested target size was met
 */
public record CompressBytesResult(byte[] bytes, long originalBytes, long resultBytes,
                                  boolean targetReached) {}
