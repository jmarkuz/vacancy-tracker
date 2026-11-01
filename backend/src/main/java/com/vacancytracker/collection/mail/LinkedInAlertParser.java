package com.vacancytracker.collection.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

/**
 * LinkedIn job alert emails (docs/sources.md Tier 2). Only the email text is read: each vacancy is a link to
 * linkedin.com/jobs/view/{id} with the title as its text and the company and place next to it. The link is
 * rewritten to its plain form and is never opened. The layout is read tolerantly (any element nesting), and an
 * email that matches nothing gives an empty list so a changed template shows up as "no vacancies", not a crash.
 */
@Component
public class LinkedInAlertParser implements MailParser {

    public static final String ID = "email-linkedin";
    static final String DOMAIN = "linkedin.com";
    private static final Pattern JOB_LINK = Pattern.compile("linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?#]*?-)?(\\d{6,})");
    private static final Pattern TEXT_LINK = Pattern.compile("https?://[^\\s<>\"]*linkedin\\.com/(?:comm/)?jobs/view/(?:[^/?#\\s]*?-)?(\\d{6,})[^\\s<>\"]*");
    private static final List<String> GENERIC = List.of("view job", "view jobs", "apply", "see all jobs", "view all jobs", "see more jobs");

    @Override
    public String adapterId() {
        return ID;
    }

    @Override
    public boolean accepts(MailMessage mail) {
        return MailParser.fromDomain(mail.from(), DOMAIN);
    }

    @Override
    public List<ParsedJob> parse(MailMessage mail) {
        List<ParsedJob> fromHtml = mail.html() == null ? List.of() : parseHtml(mail.html());
        return !fromHtml.isEmpty() || mail.text() == null ? fromHtml : parseText(mail.text());
    }

    private static String canonical(String id) {
        return "https://www.linkedin.com/jobs/view/" + id + "/";
    }

    private List<ParsedJob> parseHtml(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, Element> first = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            Matcher m = JOB_LINK.matcher(a.attr("href"));
            if (!m.find()) {
                continue;
            }
            String id = m.group(1);
            String text = a.text().trim();
            first.putIfAbsent(id, a);
            if (!text.isEmpty() && !GENERIC.contains(text.toLowerCase(java.util.Locale.ROOT)) && !titles.containsKey(id)) {
                titles.put(id, text);
                first.put(id, a);
            }
        }
        List<ParsedJob> jobs = new ArrayList<>();
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            List<String> around = linesAround(first.get(entry.getKey()), entry.getValue());
            String company = around.isEmpty() ? null : around.get(0);
            String location = around.size() > 1 ? around.get(1) : null;
            if (company != null && location == null && company.contains(" · ")) {
                String[] parts = company.split(" · ", 2);
                company = parts[0].trim();
                location = parts[1].trim();
            }
            jobs.add(new ParsedJob(entry.getKey(), canonical(entry.getKey()), entry.getValue(), company, location));
        }
        return jobs;
    }

    /**
     * The text lines of the smallest enclosing block that has more than the title, with the title and generic
     * button labels removed. The block is the table cell, list item or division the link sits in.
     */
    private static List<String> linesAround(Element link, String title) {
        Element block = link;
        List<String> lines = List.of();
        for (int depth = 0; depth < 6 && block.parent() != null; depth++) {
            block = block.parent();
            lines = leafLines(block).stream()
                    .filter(l -> !l.equals(title) && !GENERIC.contains(l.toLowerCase(java.util.Locale.ROOT)))
                    .toList();
            if (!lines.isEmpty()) {
                break;
            }
        }
        return lines.size() > 2 ? lines.subList(0, 2) : lines;
    }

    private static List<String> leafLines(Element block) {
        List<String> out = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    String s = t.text().trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // Nothing to do when leaving a node.
            }
        }, block);
        return out;
    }

    /** Plain-text version: three lines (title, company, place) followed by the link. */
    private List<ParsedJob> parseText(String text) {
        List<String> lines = text.lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
        Map<String, ParsedJob> jobs = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = TEXT_LINK.matcher(lines.get(i));
            if (!m.find() || i < 3 || jobs.containsKey(m.group(1))) {
                continue;
            }
            jobs.put(m.group(1), new ParsedJob(m.group(1), canonical(m.group(1)), lines.get(i - 3), lines.get(i - 2), lines.get(i - 1)));
        }
        return List.copyOf(jobs.values());
    }
}
