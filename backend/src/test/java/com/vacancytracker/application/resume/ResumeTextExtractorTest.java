package com.vacancytracker.application.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class ResumeTextExtractorTest {

    @Test
    void readsPlainTextAndMarkdownAndDropsTheByteOrderMark() {
        byte[] bytes = "﻿# Jane\r\n\r\n\r\n\r\nJava,  Kafka\u0007".getBytes(StandardCharsets.UTF_8);

        assertThat(ResumeTextExtractor.extract("cv.md", bytes)).isEqualTo("# Jane\n\nJava, Kafka");
    }

    @Test
    void readsADocx() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Jane Doe");
            doc.createParagraph().createRun().setText("Skills: Java, Kafka");
            doc.write(out);
        }

        assertThat(ResumeTextExtractor.extract("cv.docx", out.toByteArray())).contains("Jane Doe").contains("Skills: Java, Kafka");
    }

    @Test
    void readsAPdf() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Skills: Java, PostgreSQL");
                content.endText();
            }
            doc.save(out);
        }

        assertThat(ResumeTextExtractor.extract("cv.pdf", out.toByteArray())).isEqualTo("Skills: Java, PostgreSQL");
    }

    @Test
    void refusesWhatItCannotRead() {
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.txt", new byte[0])).hasMessageContaining("empty");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.exe", "MZ".getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("PDF, DOCX, TXT or MD");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.txt", new byte[] {(byte) 0xC3, (byte) 0x28}))
                .hasMessageContaining("not UTF-8");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.pdf", "%PDF-broken".getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("PDF could not be read");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.docx", "PK-not-a-zip".getBytes(StandardCharsets.US_ASCII)))
                .hasMessageContaining("DOCX");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("cv.txt", "   \n ".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("No text");
        assertThatThrownBy(() -> ResumeTextExtractor.extract("big.txt", new byte[ResumeTextExtractor.MAX_BYTES + 1]))
                .hasMessageContaining("5 MB");
    }
}
