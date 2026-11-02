package com.vacancytracker.vacancy;

import java.util.UUID;

public class VacancyNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VacancyNotFoundException(UUID id) {
        super("Vacancy not found: " + id);
    }
}
