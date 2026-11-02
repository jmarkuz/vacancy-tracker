package com.vacancytracker.vacancy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a posting offers visa sponsorship, read from its own words (T-078, ADR-044): true when it says so, false
 * when it says it does not (or asks for an existing right to work), null when it says nothing. A refusal wins over
 * an offer in the same text. English, German, Polish, Ukrainian and Russian phrasings are recognised; anything else
 * stays unknown, which a profile treats by its missing-value policy.
 */
public final class VisaSponsorship {

    /** UNICODE_CHARACTER_CLASS makes \w and \b treat Cyrillic letters as letters. */
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final List<Pattern> NO = List.of(
            Pattern.compile("\\b(?:no|not|unable to|cannot|can't|can not|won't|will not|do not|does not|don't|doesn't|are not able to)\\b"
                    + "[^.\\n]{0,40}\\bsponsor", FLAGS),
            Pattern.compile("\\bwithout\\s+(?:visa\\s+)?sponsorship\\b", FLAGS),
            Pattern.compile("\\bsponsorship\\b[^.\\n]{0,30}\\b(?:not available|unavailable|is not (?:available|provided|offered))\\b", FLAGS),
            Pattern.compile("\\b(?:must|need to|should|required to)\\s+(?:already\\s+)?(?:have|hold|possess)\\b[^.\\n]{0,40}"
                    + "\\b(?:right to work|work (?:permit|authori[sz]ation))\\b", FLAGS),
            Pattern.compile("\\bkein(?:e|en)?\\s+(?:visa|visum)[-\\s]?(?:sponsoring|unterstützung)\\b", FLAGS),
            Pattern.compile("\\bне\\s+(?:надаємо|забезпечуємо|спонсоруємо|предоставляем|спонсируем)\\b[^.\\n]{0,30}\\bвіз|\\bне\\s+"
                    + "(?:надаємо|забезпечуємо|спонсоруємо|предоставляем|спонсируем)\\b[^.\\n]{0,30}\\bвиз", FLAGS));
    private static final List<Pattern> YES = List.of(
            Pattern.compile("\\bvisa\\s+sponsorship\\b", FLAGS),
            Pattern.compile("\\bsponsor(?:s|ing|ship)?\\s+(?:your\\s+|a\\s+|the\\s+|work\\s+)*visas?\\b", FLAGS),
            Pattern.compile("\\bvisa\\s+(?:support|assistance|help|sponsoring)\\b", FLAGS),
            Pattern.compile("\\b(?:help|assist|support)\\w*\\s+(?:you\\s+)?with\\s+(?:the\\s+|your\\s+)?(?:work\\s+)?visas?\\b", FLAGS),
            Pattern.compile("\\brelocation\\s*(?:and|&|\\+)\\s*visa\\b|\\bvisa\\s*(?:and|&|\\+)\\s*relocation\\b", FLAGS),
            Pattern.compile("\\bvis(?:a|um)[-\\s]?(?:sponsoring|unterstützung)\\b|\\bunterstützung\\s+beim\\s+visum\\b", FLAGS),
            Pattern.compile("\\bwsparcie\\s+wizowe\\b|\\bpomoc\\s+(?:w|przy)\\s+(?:uzyskaniu\\s+)?wizy\\b", FLAGS),
            Pattern.compile("\\bвізов\\w*\\s+підтримк|\\bдопомог\\w*\\s+(?:з|в|у)\\s+(?:оформленн\\w*\\s+)?віз|\\bвизов\\w*\\s+поддержк"
                    + "|\\bпомощь\\s+(?:с|в)\\s+(?:оформлении\\s+)?виз", FLAGS));

    private VisaSponsorship() {
    }

    public static Boolean detect(String title, String description) {
        String text = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        if (NO.stream().anyMatch(p -> p.matcher(text).find())) {
            return Boolean.FALSE;
        }
        return YES.stream().anyMatch(p -> p.matcher(text).find()) ? Boolean.TRUE : null;
    }
}
