package com.vacancytracker.collection;

/** The page has no schema.org JobPosting data; the user should add the vacancy manually. */
public class NoJobPostingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoJobPostingException(String url) {
        super("No JobPosting data found at " + url + ". Add the vacancy manually.");
    }
}
