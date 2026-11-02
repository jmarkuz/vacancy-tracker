package com.vacancytracker.application.resume;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Plain text of an uploaded resume (T-080): PDF (PDFBox), DOCX (Apache POI), plain text and Markdown. The format is
 * decided by the file's own bytes and name, not by what the browser claims. Images in a scanned PDF are not read
 * (no OCR), so such a file gives an error asking for a text version.
 */
public final class ResumeTextExtractor {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_CHARS = 200_000;

    private ResumeTextExtractor() {
    }

    public static String extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedResumeException("The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new UnsupportedResumeException("The file is larger than 5 MB.");
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text;
        if (startsWith(bytes, "%PDF")) {
            text = pdf(bytes);
        } else if (startsWith(bytes, "PK") && (name.endsWith(".docx") || name.isEmpty())) {
            text = docx(bytes);
        } else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown") || name.isEmpty()) {
            text = utf8(bytes);
        } else {
            throw new UnsupportedResumeException("Use a PDF, DOCX, TXT or MD file.");
        }
        String clean = normalize(text);
        if (clean.isBlank()) {
            throw new UnsupportedResumeException("No text was found in the file. A scanned PDF has no text layer: "
                    + "upload a text PDF, a DOCX or paste the text.");
        }
        return clean;
    }

    /** Line endings unified, control characters dropped, runs of blank lines shortened, length capped. */
    public static String normalize(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t;
    }

    private static String pdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (InvalidPasswordException e) {
            throw new UnsupportedResumeException("The PDF is password-protected. Save it without a password and upload again.");
        } catch (IOException e) {
            throw new UnsupportedResumeException("The PDF could not be read: " + e.getMessage());
        }
    }

    private static String docx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | org.apache.poi.UnsupportedFileFormatException | IllegalStateException e) {
            throw new UnsupportedResumeException("The DOCX file could not be read. Save it again as .docx (Word 2007 or later).");
        }
    }

    private static String utf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new UnsupportedResumeException("The text file is not UTF-8. Save it as UTF-8 and upload again.");
        }
    }

    private static boolean startsWith(byte[] bytes, String magic) {
        byte[] m = magic.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < m.length) {
            return false;
        }
        for (int i = 0; i < m.length; i++) {
            if (bytes[i] != m[i]) {
                return false;
            }
        }
        return true;
    }
}
