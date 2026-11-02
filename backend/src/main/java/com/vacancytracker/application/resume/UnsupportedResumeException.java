package com.vacancytracker.application.resume;

/** The uploaded resume cannot be read. The message says what to do instead; it is shown to the user. */
public class UnsupportedResumeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnsupportedResumeException(String message) {
        super(message);
    }
}
