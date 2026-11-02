package com.vacancytracker.application.documents;

import java.util.List;
import java.util.stream.Collectors;

/** The text has facts its base draft does not have, and the owner has not confirmed them (T-098). Answered as 422. */
public class FactsNotConfirmedException extends IllegalArgumentException {

    private final transient List<FactCheck.Finding> findings;

    public FactsNotConfirmedException(List<FactCheck.Finding> findings) {
        super("Not in your base draft: " + findings.stream().map(f -> f.value() + " (" + f.category() + ")").collect(Collectors.joining(", "))
                + ". Fix the text, or confirm these facts are true, before saving.");
        this.findings = List.copyOf(findings);
    }

    public List<FactCheck.Finding> findings() {
        return findings;
    }
}
