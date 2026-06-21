package com.pdfconduit.app.cli;

import picocli.CommandLine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts strings like "5MB", "500KB", "1.5MB" to bytes. */
public class SizeConverter implements CommandLine.ITypeConverter<Long> {

    private static final Pattern PATTERN = Pattern.compile(
        "^([0-9]+(?:\\.[0-9]+)?)\\s*(KB|MB|GB|B)?$", Pattern.CASE_INSENSITIVE);

    @Override
    public Long convert(String value) throws Exception {
        Matcher m = PATTERN.matcher(value.strip());
        if (!m.matches()) {
            throw new CommandLine.TypeConversionException(
                "Invalid size: \"" + value + "\". Examples: 500KB, 5MB, 1.5MB");
        }
        double amount = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "B" : m.group(2).toUpperCase();
        return switch (unit) {
            case "B"  -> (long) amount;
            case "KB" -> (long)(amount * 1024);
            case "MB" -> (long)(amount * 1024 * 1024);
            case "GB" -> (long)(amount * 1024 * 1024 * 1024);
            default   -> throw new CommandLine.TypeConversionException("Unknown unit: " + unit);
        };
    }
}
