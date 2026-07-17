package com.pdfconduit.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PDF Conduit web backend.
 *
 * <p>This module is a thin, <b>stateless, in-memory, API-only</b> transport layer over
 * {@code pdf-utils-core}: every PDF operation runs through the core {@code byte[]} API
 * ({@code MemoryOperations} / the operations' {@code executeBytes} variants) and streams
 * the result bytes straight back. No PDF logic and no disk state live here — the sole disk
 * touch is the documented office-conversion exception (LibreOffice, gated by config).
 * The UI is now a separate Angular frontend; this backend serves no static pages.
 */
@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
