package com.vacancytracker.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vacancytracker.common.LogMasking;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** Masks every text value of a structured (JSON) log line: message, stack trace and key-value fields. */
public class MaskingJsonCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, LogMasking::mask));
    }
}
