package org.example.core.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SizeEstimator {

    public static long estimateBytes(PDDocument doc) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        doc.save(buf);
        return buf.size();
    }
}
