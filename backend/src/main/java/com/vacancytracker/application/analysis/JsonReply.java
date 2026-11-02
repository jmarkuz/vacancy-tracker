package com.vacancytracker.application.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.application.ai.AiFailedException;

/** Reads the JSON object of a model's answer, tolerating a code fence or words around it. */
final class JsonReply {

    private JsonReply() {
    }

    static JsonNode parse(ObjectMapper mapper, String reply) {
        int start = reply == null ? -1 : reply.indexOf('{');
        int end = reply == null ? -1 : reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AiFailedException("Claude did not answer with the expected data. Try again.");
        }
        try {
            return mapper.readTree(reply.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new AiFailedException("Claude's answer was not valid data. Try again.");
        }
    }
}
