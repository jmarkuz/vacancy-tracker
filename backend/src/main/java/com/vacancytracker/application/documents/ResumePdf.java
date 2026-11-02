package com.vacancytracker.application.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The resume as a PDF (T-097, ADR-050): A4, one column, real text in an embedded font that covers Cyrillic (Liberation
 * Sans, SIL Open Font License, in resources/fonts), body text at 10.5 pt, no tables, icons, photos or text boxes. It
 * reads the plain Markdown the documents use: "#" name, "##" section, "###" role, "- " bullets and paragraphs;
 * emphasis marks are dropped and a link shows its text and address. A resume may have at most {@link #MAX_PAGES}.
 */
public final class ResumePdf {

    public static final int MAX_PAGES = 2;
    static final float BODY = 10.5f;
    private static final float NAME = 18f;
    private static final float SECTION = 12.5f;
    private static final float ROLE = 11f;
    private static final float LEADING = 1.32f;
    private static final float MARGIN_X = 54f;
    private static final float MARGIN_Y = 50f;
    private static final float BULLET_INDENT = 12f;
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|\\*|`)");

    /** A rendered PDF and its page count. */
    public record Rendered(byte[] bytes, int pages) {
    }

    private ResumePdf() {
    }

    public static Rendered render(String markdown) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font regular = font(doc, "LiberationSans-Regular.ttf");
            PDType0Font bold = font(doc, "LiberationSans-Bold.ttf");
            Writer w = new Writer(doc, regular);
            for (String raw : markdown.replace("\r\n", "\n").split("\n")) {
                String line = raw.replace('\t', ' ').stripTrailing();
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    w.space(BODY * 0.5f);
                } else if (trimmed.startsWith("### ")) {
                    w.space(ROLE * 0.35f);
                    w.paragraph(inline(trimmed.substring(4)), bold, ROLE, 0, null);
                } else if (trimmed.startsWith("## ")) {
                    w.space(SECTION * 0.6f);
                    w.paragraph(inline(trimmed.substring(3)).toUpperCase(java.util.Locale.ROOT), bold, SECTION, 0, null);
                    w.rule();
                } else if (trimmed.startsWith("# ")) {
                    w.paragraph(inline(trimmed.substring(2)), bold, NAME, 0, null);
                } else if (trimmed.matches("^[-*•] .*")) {
                    w.paragraph(inline(trimmed.substring(2)), regular, BODY, BULLET_INDENT, "•");
                } else {
                    w.paragraph(inline(trimmed), regular, BODY, 0, null);
                }
            }
            w.close();
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Resume");
            info.setCreator("Vacancy Tracker");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new Rendered(out.toByteArray(), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the resume PDF", e);
        }
    }

    /** The page count of a PDF, or -1 when it cannot be read. */
    public static int pages(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String inline(String text) {
        return EMPHASIS.matcher(LINK.matcher(text).replaceAll("$1 ($2)")).replaceAll("").replace(' ', ' ');
    }

    private static PDType0Font font(PDDocument doc, String name) throws IOException {
        try (InputStream in = ResumePdf.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing font resource /fonts/" + name);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    /** Lays out lines top to bottom, starting a new A4 page when the current one is full. */
    private static final class Writer {

        private final PDDocument doc;
        private final PDType0Font regular;
        private final Map<PDType0Font, Map<Integer, Boolean>> glyphs = new HashMap<>();
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN_X;
        private PDPageContentStream page;
        private float y;

        Writer(PDDocument doc, PDType0Font regular) throws IOException {
            this.doc = doc;
            this.regular = regular;
            newPage();
        }

        private void newPage() throws IOException {
            if (page != null) {
                page.close();
            }
            PDPage p = new PDPage(PDRectangle.A4);
            doc.addPage(p);
            page = new PDPageContentStream(doc, p);
            y = PDRectangle.A4.getHeight() - MARGIN_Y;
        }

        void space(float points) {
            y -= points;
        }

        void rule() throws IOException {
            ensure(4);
            y -= 2;
            page.setLineWidth(0.5f);
            page.moveTo(MARGIN_X, y);
            page.lineTo(MARGIN_X + width, y);
            page.stroke();
            y -= 4;
        }

        /** Wraps the text to the column; a bullet hangs in front of the first line. */
        void paragraph(String text, PDType0Font font, float size, float indent, String bullet) throws IOException {
            String safe = encodable(font, text);
            List<String> lines = wrap(safe, font, size, width - indent);
            float leading = size * LEADING;
            for (int i = 0; i < lines.size(); i++) {
                ensure(leading);
                y -= leading;
                if (i == 0 && bullet != null) {
                    write(regular, size, MARGIN_X + 2, bullet);
                }
                write(font, size, MARGIN_X + indent, lines.get(i));
            }
        }

        private void ensure(float needed) throws IOException {
            if (y - needed < MARGIN_Y) {
                newPage();
            }
        }

        private void write(PDType0Font font, float size, float x, String text) throws IOException {
            page.beginText();
            page.setFont(font, size);
            page.newLineAtOffset(x, y);
            page.showText(text);
            page.endText();
        }

        private List<String> wrap(String text, PDType0Font font, float size, float max) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" +")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= max) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                // A word wider than the column (a long link) is broken where it overflows, wherever it stands.
                String rest = word;
                while (width(font, size, rest) > max) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(font, size, rest.substring(0, cut)) > max) {
                        cut--;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            if (!line.isEmpty() || lines.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private static float width(PDType0Font font, float size, String text) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        /** Characters the font has no glyph for (emoji, rare symbols) become "?" instead of failing the render. */
        private String encodable(PDType0Font font, String text) {
            Map<Integer, Boolean> known = glyphs.computeIfAbsent(font, f -> new HashMap<>());
            StringBuilder out = new StringBuilder(text.length());
            text.codePoints().forEach(cp -> {
                boolean ok = known.computeIfAbsent(cp, c -> {
                    try {
                        font.encode(new String(Character.toChars(c)));
                        return true;
                    } catch (IllegalArgumentException | IOException e) {
                        return false;
                    }
                });
                out.appendCodePoint(ok ? cp : '?');
            });
            return out.toString();
        }

        void close() throws IOException {
            page.close();
        }
    }
}
