package com.vacancytracker.collection.adapter.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The robots.txt rules that apply to this tool (RFC 9309). The group of the most specific matching user agent is
 * used, or the "*" group when none matches. Within it the longest matching path pattern wins and Allow wins a
 * tie; "*" matches any run of characters and a trailing "$" anchors the end. An empty Disallow allows everything.
 */
final class RobotsRules {

    private record Rule(boolean allow, Pattern pattern, int length) {
    }

    private static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), 0);

    private final List<Rule> rules;
    private final int crawlDelaySeconds;

    private RobotsRules(List<Rule> rules, int crawlDelaySeconds) {
        this.rules = rules;
        this.crawlDelaySeconds = crawlDelaySeconds;
    }

    /** No robots.txt (a 4xx answer): everything may be fetched. */
    static RobotsRules allowAll() {
        return ALLOW_ALL;
    }

    int crawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    boolean isAllowed(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(path).lookingAt()
                    && (best == null || rule.length() > best.length() || (rule.length() == best.length() && rule.allow() && !best.allow()))) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** agent is the product name to match, compared without case. */
    static RobotsRules parse(String body, String agent) {
        String me = agent.toLowerCase(Locale.ROOT);
        List<String> groupAgents = new ArrayList<>();
        List<Rule> groupRules = new ArrayList<>();
        int groupDelay = 0;
        boolean collectingAgents = true;

        int bestSpecificity = -1;
        List<Rule> bestRules = List.of();
        int bestDelay = 0;

        List<String> lines = new ArrayList<>(body.lines().toList());
        lines.add("user-agent: \u0000end"); // a sentinel that closes the last group
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (field.equals("user-agent")) {
                if (!collectingAgents) {
                    int specificity = specificity(groupAgents, me);
                    if (specificity > bestSpecificity) {
                        bestSpecificity = specificity;
                        bestRules = List.copyOf(groupRules);
                        bestDelay = groupDelay;
                    } else if (specificity == bestSpecificity && specificity >= 0) {
                        List<Rule> merged = new ArrayList<>(bestRules);
                        merged.addAll(groupRules);
                        bestRules = merged;
                        bestDelay = Math.max(bestDelay, groupDelay);
                    }
                    groupAgents = new ArrayList<>();
                    groupRules = new ArrayList<>();
                    groupDelay = 0;
                    collectingAgents = true;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
            } else if (field.equals("allow") || field.equals("disallow")) {
                collectingAgents = false;
                if (!value.isEmpty()) {
                    groupRules.add(new Rule(field.equals("allow"), toPattern(value), value.length()));
                }
            } else if (field.equals("crawl-delay")) {
                collectingAgents = false;
                try {
                    groupDelay = Math.max(0, (int) Math.ceil(Double.parseDouble(value)));
                } catch (NumberFormatException e) {
                    groupDelay = 0;
                }
            }
        }
        return new RobotsRules(bestRules, bestDelay);
    }

    /** Length of the longest agent name that matches, 0 for "*", -1 for no match. */
    private static int specificity(List<String> agents, String me) {
        int best = -1;
        for (String agent : agents) {
            if (agent.equals("*")) {
                best = Math.max(best, 0);
            } else if (!agent.isEmpty() && me.contains(agent)) {
                best = Math.max(best, agent.length());
            }
        }
        return best;
    }

    private static Pattern toPattern(String value) {
        boolean anchored = value.endsWith("$");
        String core = anchored ? value.substring(0, value.length() - 1) : value;
        List<String> parts = new ArrayList<>();
        for (String part : core.split("\\*", -1)) {
            parts.add(Pattern.quote(part));
        }
        String regex = String.join(".*", parts);
        return Pattern.compile(regex + (anchored ? "$" : ""));
    }
}
