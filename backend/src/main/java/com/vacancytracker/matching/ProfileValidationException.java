package com.vacancytracker.matching;

import java.util.Map;

/** One or more problems in a profile, each with a path such as criteria[2].operator. Reported as 422. */
public class ProfileValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> errors;

    public ProfileValidationException(Map<String, String> errors) {
        super("Profile is not valid: " + errors.size() + " problem(s)");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
