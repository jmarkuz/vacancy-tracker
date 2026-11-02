package com.vacancytracker.common;

/** A requested resource does not exist. Mapped to 404 by the API exception handler. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
