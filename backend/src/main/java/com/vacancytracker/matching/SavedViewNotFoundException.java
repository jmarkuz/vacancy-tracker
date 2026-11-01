package com.vacancytracker.matching;

import java.util.UUID;

public class SavedViewNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SavedViewNotFoundException(UUID id) {
        super("Saved view not found: " + id);
    }
}
