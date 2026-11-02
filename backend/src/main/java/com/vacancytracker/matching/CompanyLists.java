package com.vacancytracker.matching;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A snapshot of all company list entries, asked "what applies to this company under this profile". */
public final class CompanyLists {

    public static final CompanyLists EMPTY = new CompanyLists(List.of());

    private final Set<String> blocked = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();

    public CompanyLists(List<CompanyListEntry> entries) {
        for (CompanyListEntry e : entries) {
            (e.listType().equals("blocklist") ? blocked : allowed).add(key(e.profileId()) + "|" + normalize(e.company()));
        }
    }

    /** Lower case with single spaces, so "Acme  Corp" and "acme corp" are the same company. */
    public static String normalize(String company) {
        return company == null ? "" : company.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public ListVerdict verdict(UUID profileId, String company) {
        String name = normalize(company);
        if (name.isEmpty()) {
            return ListVerdict.NONE;
        }
        if (blocked.contains(key(null) + "|" + name) || blocked.contains(key(profileId) + "|" + name)) {
            return ListVerdict.BLOCKED;
        }
        if (allowed.contains(key(null) + "|" + name) || allowed.contains(key(profileId) + "|" + name)) {
            return ListVerdict.ALLOWED;
        }
        return ListVerdict.NONE;
    }

    private static String key(UUID profileId) {
        return profileId == null ? "*" : profileId.toString();
    }
}
