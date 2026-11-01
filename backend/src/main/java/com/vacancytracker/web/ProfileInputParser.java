package com.vacancytracker.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.vacancytracker.matching.ProfileInput;
import com.vacancytracker.matching.ProfileValidationException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads a profile request strictly: an unknown property is an error, not silently ignored, so a typo such as
 * "weigth" cannot quietly drop a setting. Problems are reported with the same path format as validation.
 */
@Component
public class ProfileInputParser {

    private final ObjectMapper strict;

    public ProfileInputParser(ObjectMapper mapper) {
        this.strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ProfileInput parse(JsonNode body) {
        try {
            return strict.treeToValue(body, ProfileInput.class);
        } catch (JsonMappingException e) {
            String path = path(e);
            String message = e instanceof UnrecognizedPropertyException
                    ? "Unknown property"
                    : "Wrong type or value";
            throw new ProfileValidationException(Map.of(path.isEmpty() ? "profile" : path, message));
        } catch (JsonProcessingException e) {
            throw new ProfileValidationException(Map.of("profile", "Not a valid profile"));
        }
    }

    /** criteria[2].weigth from the Jackson path. */
    private static String path(JsonMappingException e) {
        StringBuilder out = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                out.append(out.length() == 0 ? "" : ".").append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                out.append('[').append(ref.getIndex()).append(']');
            }
        }
        return out.toString();
    }
}
