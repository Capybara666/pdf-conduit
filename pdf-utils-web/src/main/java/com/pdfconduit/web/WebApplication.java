package com.pdfconduit.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PDF Conduit web backend.
 *
 * <p>This module is a thin transport layer over {@code pdf-utils-core}: every PDF
 * operation is executed by the same stateless, filesystem-oriented core library
 * that powers the desktop GUI and CLI. No PDF logic lives here — only HTTP plumbing,
 * temp-file management and result streaming.
 */
@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
