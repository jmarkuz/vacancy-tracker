package com.vacancytracker.application.documents;

/** The resume's PDF would have more than two pages (T-097); nothing was saved. Answered as 422 with the message. */
public class ResumeTooLongException extends IllegalArgumentException {

    private final int pages;

    public ResumeTooLongException(int pages, String message) {
        super(message);
        this.pages = pages;
    }

    public int pages() {
        return pages;
    }
}
