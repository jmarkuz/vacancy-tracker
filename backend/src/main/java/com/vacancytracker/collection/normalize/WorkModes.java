package com.vacancytracker.collection.normalize;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the work mode (remote, hybrid, onsite) where a source states it. A source's own field wins; otherwise the
 * location is read first, then the title, and the description last, because a place such as "Remote - EU" is a
 * statement while a sentence in a description is weaker. Hybrid is checked before remote. Words are read in English,
 * German, Czech, Polish, Ukrainian and Russian. Nothing is guessed when no word is found.
 */
final class WorkModes {

    /** Phrases that contain a mode word without stating one ("hybrid cloud", "remote sensing"), and negations. */
    private static final Pattern NOT_A_MODE = Pattern.compile(
            "\\b(?:hybrid\\s+(?:cloud|multi-?cloud|app|apps|application|mobile|infrastructure|environments?|it)"
                    + "|remote\\s+(?:sensing|desktop|access|control|code|repositor\\w*|teams?|colleagues|stakeholders))\\b"
                    + "|\\b(?:not|non|no|isn't|is\\s+not|without)[\\s-]+(?:a\\s+|an\\s+|fully\\s+|100%\\s+)?(?:remote|hybrid)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HYBRID = Pattern.compile("hybrid|гібрид|гибрид");
    private static final Pattern REMOTE = Pattern.compile("remote|work from home|\\bwfh\\b|home[ -]?office|telecommut"
            + "|vzdálen|z domova|zdaln|віддален|дистанційн|удален|удалён|дистанцион");
    private static final Pattern ONSITE = Pattern.compile("onsite|on-site|on site|in-office|in office|office-based|vor ort"
            + "|stacjonarn|в офісі|в офисе");
    private static final Pattern ANYWHERE = Pattern.compile("\\b(?:anywhere|worldwide)\\b", Pattern.CASE_INSENSITIVE);
    /** A mode word in a title only counts when it is set off ("(Remote)", "- Hybrid", "Remote Java Developer"). */
    private static final Pattern TITLE_MODE = Pattern.compile(
            "[(\\[|,/–—-]\\s*(?:fully\\s+)?(?:remote|hybrid|on-?site)\\b"
                    + "|\\b(?:remote|hybrid|on-?site)\\s*[)\\]|]"
                    + "|^\\s*(?:fully\\s+)?(?:remote|hybrid)\\b",
            Pattern.CASE_INSENSITIVE);

    private WorkModes() {
    }

    /** The mode a source's own work-mode field names, or null when it names none ("unspecified", "any"). */
    static String fromField(String value) {
        return modeOf(value);
    }

    /** The mode stated by the location, then the title, then the description; null when none states one. */
    static String detect(String location, String title, String description) {
        String fromLocation = modeOf(location);
        if (fromLocation == null && location != null && ANYWHERE.matcher(location).find()) {
            fromLocation = "remote";
        }
        if (fromLocation != null) {
            return fromLocation;
        }
        String fromTitle = modeOfTitle(title);
        return fromTitle != null ? fromTitle : modeOf(description);
    }

    private static String modeOfTitle(String title) {
        if (title == null) {
            return null;
        }
        String cleaned = NOT_A_MODE.matcher(title).replaceAll(" ");
        return TITLE_MODE.matcher(cleaned).find() ? modeOf(cleaned) : null;
    }

    private static String modeOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = NOT_A_MODE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        if (HYBRID.matcher(lower).find()) {
            return "hybrid";
        }
        if (REMOTE.matcher(lower).find()) {
            return "remote";
        }
        return ONSITE.matcher(lower).find() ? "onsite" : null;
    }
}
