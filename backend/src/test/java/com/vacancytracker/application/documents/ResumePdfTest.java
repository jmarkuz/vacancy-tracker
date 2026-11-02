package com.vacancytracker.application.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

public class ResumePdfTest {

    public static final String RESUME = """
            # Олена Коваль
            Київ, Україна | olena@example.com | +380 50 000 0000 | [LinkedIn](https://linkedin.com/in/olena)

            ## Summary
            Backend engineer with 8 years in payments: **Java**, Kafka and PostgreSQL at scale.

            ## Experience

            ### Senior Backend Engineer, Acme Pay, Remote
            Jan 2020 to Present
            - Cut payment latency by 40% by moving settlement to Kafka streams.
            - Led a team of 5 engineers through the PostgreSQL 16 migration with zero downtime.

            ### Backend Engineer, Beta Bank, Kyiv
            Mar 2016 to Dec 2019
            - Розробила сервіс платежів на Java, що обробляє 2 млн транзакцій на день.

            ## Education
            MSc Computer Science, KPI, 2016

            ## Skills
            Languages: Java, Kotlin, SQL
            """;

    /** A resume of the given number of bullets, long enough to run over two pages from about 110 bullets. */
    public static String longResume(int bullets) {
        StringBuilder text = new StringBuilder("# Jane Doe\nKyiv | jane@example.com\n\n## Experience\n\n### Backend Engineer, Acme, Remote\nJan 2010 to Present\n");
        for (int i = 1; i <= bullets; i++) {
            text.append("- Delivered improvement number ").append(i).append(" to the payment platform with Java and PostgreSQL.\n");
        }
        return text.append("\n## Skills\nJava, PostgreSQL\n").toString();
    }

    @Test
    void theResumeIsAnA4PdfWithRealTextInTheOriginalOrderAndAnEmbeddedCyrillicFont() throws IOException {
        ResumePdf.Rendered pdf = ResumePdf.render(RESUME);

        assertThat(pdf.pages()).isEqualTo(1);
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            PDPage page = doc.getPage(0);
            assertThat(page.getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(page.getMediaBox().getHeight()).isEqualTo(PDRectangle.A4.getHeight());
            for (var name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                assertThat(font.isEmbedded()).as(font.getName()).isTrue();
                assertThat(font.getName()).contains("LiberationSans");
            }
            List<Float> sizes = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    positions.forEach(p -> sizes.add(p.getFontSizeInPt()));
                    super.writeString(text, positions);
                }
            };
            String text = stripper.getText(doc);
            // Every part is there, as text, in the order of the resume.
            List<String> parts = List.of("Олена Коваль", "olena@example.com", "LinkedIn (https://linkedin.com/in/olena)", "SUMMARY",
                    "Backend engineer with 8 years in payments: Java, Kafka", "EXPERIENCE", "Senior Backend Engineer, Acme Pay, Remote",
                    "Cut payment latency by 40%", "Led a team of 5 engineers", "Backend Engineer, Beta Bank, Kyiv",
                    "Розробила сервіс платежів на Java", "EDUCATION", "MSc Computer Science, KPI, 2016", "SKILLS", "Languages: Java, Kotlin, SQL");
            int at = -1;
            for (String part : parts) {
                int next = text.indexOf(part, at + 1);
                assertThat(next).as("\"%s\" after position %d in:%n%s", part, at, text).isGreaterThan(at);
                at = next;
            }
            assertThat(text).doesNotContain("**").doesNotContain("## ");
            assertThat(sizes).isNotEmpty().allSatisfy(size -> assertThat(size).isGreaterThanOrEqualTo(10f));
        }
    }

    @Test
    void pagesAreCountedAndALongResumeRunsOverTwo() {
        assertThat(ResumePdf.render(longResume(40)).pages()).isEqualTo(1);
        assertThat(ResumePdf.render(longResume(300)).pages()).isGreaterThan(ResumePdf.MAX_PAGES);
        assertThat(ResumePdf.pages(ResumePdf.render(longResume(300)).bytes())).isGreaterThan(4);
        assertThat(ResumePdf.pages("not a pdf".getBytes())).isEqualTo(-1);
    }

    @Test
    void aLongLinkAfterOrdinaryTextIsBrokenInsideTheColumn() throws IOException {
        String link = "https://github.com/jane-doe/" + "a-very-long-repository-name-without-any-spaces".repeat(3);
        ResumePdf.Rendered pdf = ResumePdf.render("# Jane Doe\n- Portfolio and code samples: " + link);
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            float right = PDRectangle.A4.getWidth();
            List<Float> ends = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    positions.forEach(p -> ends.add(p.getXDirAdj() + p.getWidthDirAdj()));
                    super.writeString(text, positions);
                }
            };
            String text = stripper.getText(doc).replaceAll("\\s+", "");
            assertThat(text).contains(link.replaceAll("\\s+", ""));
            assertThat(ends).allSatisfy(x -> assertThat(x).isLessThan(right - 40));
        }
    }

    @Test
    void charactersWithoutAGlyphDoNotBreakTheRender() throws IOException {
        ResumePdf.Rendered pdf = ResumePdf.render("# Jane 🚀 Doe\n- Shipped 😀 things");
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Jane").contains("Shipped");
        }
    }
}
