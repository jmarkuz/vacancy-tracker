package com.vacancytracker.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.vacancytracker.common.LogMasking;

/** The log message with secrets masked (conversion word maskedMsg in logback-spring.xml). */
public class MaskedMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasking.mask(super.convert(event));
    }
}
