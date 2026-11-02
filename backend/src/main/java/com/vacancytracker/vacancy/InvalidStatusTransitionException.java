package com.vacancytracker.vacancy;

public class InvalidStatusTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidStatusTransitionException(VacancyStatus from, VacancyStatus to) {
        super("Cannot move from " + from.label() + " to " + to.label());
    }
}
