package com.pdfconduit.core.service;

/**
 * An in-memory output document: its bytes plus a suggested file name (with extension).
 * The stateless surfaces (web, in-memory pipeline) carry results as these so a caller
 * can stream or ZIP them without ever touching disk.
 *
 * @param filename suggested output name, e.g. {@code report_compressed.pdf}
 * @param data     the file contents
 */
public record NamedBytes(String filename, byte[] data) {}
