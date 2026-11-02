package com.vacancytracker.application.ai;

/** Claude was asked but gave no usable answer. The message is for the owner and never holds a key or a token. */
public class AiFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiFailedException(String message) {
        super(message);
    }
}
