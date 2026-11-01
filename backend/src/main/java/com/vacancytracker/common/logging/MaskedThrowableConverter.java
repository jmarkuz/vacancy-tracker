package com.vacancytracker.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import com.vacancytracker.common.LogMasking;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Stack traces with secrets masked: exception messages often carry the address that was called. */
public class MaskedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasking.mask(super.throwableProxyToString(tp));
    }
}
