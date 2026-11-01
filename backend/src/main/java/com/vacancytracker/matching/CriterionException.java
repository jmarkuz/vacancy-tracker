package com.vacancytracker.matching;

/** A criterion that is not valid for its field or operator. Reported as 422. */
public class CriterionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public CriterionException(String message) {
        super(message);
    }
}
