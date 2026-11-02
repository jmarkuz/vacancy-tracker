package com.vacancytracker.application.preset;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The {{placeholder}} marks of the default texts (T-093). A field that still has one is not ready for generation. */
public final class Placeholders {

    private static final Pattern MARK = Pattern.compile("\\{\\{([^{}\\n]{1,200})}}");

    private Placeholders() {
    }

    /** The distinct placeholders of the text, as written between the braces, in order of appearance. */
    public static List<String> of(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = MARK.matcher(text);
        while (m.find()) {
            found.add(m.group(1).strip());
        }
        return List.copyOf(found);
    }
}
