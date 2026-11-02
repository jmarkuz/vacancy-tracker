package com.vacancytracker.application.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * Turns a saved document (Markdown-like text) into a plain DOCX that applicant tracking systems read well: headings
 * from "#" lines, bullets from "-" or "*" lines, **bold** spans, everything else as paragraphs. No tables or images.
 */
public final class DocxWriter {

    private DocxWriter() {
    }

    public static byte[] write(String text) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String raw : text.split("\\R", -1)) {
                String line = raw.stripTrailing();
                XWPFParagraph p = doc.createParagraph();
                if (line.startsWith("#")) {
                    int level = Math.min(3, (int) line.chars().takeWhile(c -> c == '#').count());
                    XWPFRun run = p.createRun();
                    run.setBold(true);
                    run.setFontSize(level == 1 ? 18 : level == 2 ? 14 : 12);
                    run.setText(line.substring(level).strip());
                    if (level == 1) {
                        p.setAlignment(ParagraphAlignment.LEFT);
                    }
                } else if (line.matches("^\\s*[-*•]\\s+.*")) {
                    p.setIndentationLeft(360);
                    runs(p, "• " + line.replaceFirst("^\\s*[-*•]\\s+", ""));
                } else {
                    runs(p, line);
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes text with **bold** spans as separate runs. */
    private static void runs(XWPFParagraph p, String line) {
        String[] parts = line.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                XWPFRun run = p.createRun();
                run.setBold(i % 2 == 1);
                run.setText(parts[i]);
            }
        }
    }
}
