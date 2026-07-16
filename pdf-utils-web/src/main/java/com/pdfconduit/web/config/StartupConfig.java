package com.pdfconduit.web.config;

import com.pdfconduit.core.convert.DocumentConverter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

/**
 * Applies web configuration at startup: registers an explicit LibreOffice path (if
 * given) with the shared core converter, and resolves + creates the base work
 * directory that {@link com.pdfconduit.web.support.TempWorkspace} carves per-request
 * temp dirs out of.
 */
@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class StartupConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupConfig.class);

    private final WebProperties props;
    private Path baseWorkDir;

    public StartupConfig(WebProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        if (props.hasSofficePath()) {
            DocumentConverter.setSofficeOverride(props.sofficePath());
            log.info("Using configured LibreOffice binary: {}", props.sofficePath());
        }
        this.baseWorkDir = resolveBaseDir();
        try {
            Files.createDirectories(baseWorkDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create work directory " + baseWorkDir, e);
        }
        log.info("PDF Conduit web work directory: {}", baseWorkDir);
    }

    private Path resolveBaseDir() {
        String configured = props.workDir();
        if (configured == null || configured.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir"), "pdfconduit-web");
        }
        return Path.of(configured);
    }

    /** The resolved, existing base directory for per-request temp workspaces. */
    public Path baseWorkDir() {
        return baseWorkDir;
    }
}
