package com.vacancytracker.collection.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Shared reading of job alert emails whose vacancies are links (docs/sources.md Tier 2). Only the email text is
 * read: each vacancy is a link to a job page with the title as its text and the company and place next to it.
 * The link is rewritten to its plain form and is never opened. The layout is read tolerantly (any element
 * nesting), and an email that matches nothing gives an empty list, so a changed template shows up as "no
 * vacancies", not as a crash. A sender's parser only says which domain, which links and which button labels.
 */
public abstract class LinkAlertParser implements MailParser {

    private final String adapterId;
    private final String domain;
    private final Pattern jobLink;
    private final BiFunction<String, String, String> canonical;
    private final List<String> generic;

    /**
     * jobLink finds a vacancy link and captures its id in group 1. canonical builds the plain link from the id and
     * the host the email used. generic lists button and footer labels (lower case) that are not vacancy titles.
     */
    protected LinkAlertParser(String adapterId, String domain, Pattern jobLink, BiFunction<String, String, String> canonical,
                              List<String> generic) {
        this.adapterId = adapterId;
        this.domain = domain;
        this.jobLink = jobLink;
        this.canonical = canonical;
        this.generic = generic;
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public boolean accepts(MailMessage mail) {
        return MailParser.fromDomain(mail.from(), domain);
    }

    @Override
    public List<ParsedJob> parse(MailMessage mail) {
        List<ParsedJob> fromHtml = mail.html() == null ? List.of() : parseHtml(mail.html());
        return !fromHtml.isEmpty() || mail.text() == null ? fromHtml : parseText(mail.text());
    }

    private String plain(String id, String href) {
        String host = null;
        try {
            host = java.net.URI.create(href.trim()).getHost();
        } catch (IllegalArgumentException e) {
            // An odd link: the parser's default host is used.
        }
        // The host comes from the email, which anyone can forge: only a host of the sender's own site is used.
        boolean own = host != null && (host.equalsIgnoreCase(domain) || host.toLowerCase(java.util.Locale.ROOT).endsWith("." + domain));
        return canonical.apply(id, own ? host : null);
    }

    private List<ParsedJob> parseHtml(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, Element> first = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> hrefs = new LinkedHashMap<>();
        for (Element a : doc.select("a[href]")) {
            Matcher m = jobLink.matcher(a.attr("href"));
            if (!m.find()) {
                continue;
            }
            String id = m.group(1);
            String text = a.text().trim();
            first.putIfAbsent(id, a);
            hrefs.putIfAbsent(id, a.attr("href"));
            if (!text.isEmpty() && !generic.contains(text.toLowerCase(java.util.Locale.ROOT)) && !titles.containsKey(id)) {
                titles.put(id, text);
                first.put(id, a);
                hrefs.put(id, a.attr("href"));
            }
        }
        List<ParsedJob> jobs = new ArrayList<>();
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            List<String> around = linesAround(first.get(entry.getKey()), entry.getValue(), first.values());
            String company = around.isEmpty() ? null : around.get(0);
            String location = around.size() > 1 ? around.get(1) : null;
            if (company != null && location == null && company.contains(" · ")) {
                String[] parts = company.split(" · ", 2);
                company = parts[0].trim();
                location = parts[1].trim();
            }
            jobs.add(new ParsedJob(entry.getKey(), plain(entry.getKey(), hrefs.get(entry.getKey())), entry.getValue(), company, location));
        }
        return jobs;
    }

    /**
     * The text lines of the smallest enclosing block that has more than the title, with the title and generic
     * button labels removed. The block is the table cell, list item or division the link sits in, and never one that
     * also holds another posting's link.
     */
    private List<String> linesAround(Element link, String title, java.util.Collection<Element> allLinks) {
        Element block = link;
        List<String> lines = List.of();
        for (int depth = 0; depth < 6 && block.parent() != null; depth++) {
            block = block.parent();
            Element enclosing = block;
            if (allLinks.stream().anyMatch(other -> other != link && other.parents().contains(enclosing))) {
                // The block holds another posting too: its lines would be shared, so none of them is this one's own.
                break;
            }
            lines = leafLines(block).stream()
                    .filter(l -> !l.equals(title) && !generic.contains(l.toLowerCase(java.util.Locale.ROOT)))
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
            String line = lines.get(i);
            Matcher m = jobLink.matcher(line);
            if (!line.startsWith("http") || !m.find() || i < 3 || jobs.containsKey(m.group(1))) {
                continue;
            }
            jobs.put(m.group(1), new ParsedJob(m.group(1), plain(m.group(1), line), lines.get(i - 3), lines.get(i - 2), lines.get(i - 1)));
        }
        return List.copyOf(jobs.values());
    }
}
