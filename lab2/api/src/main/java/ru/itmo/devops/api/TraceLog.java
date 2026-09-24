package ru.itmo.devops.api;

import org.slf4j.Logger;
import org.slf4j.MDC;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

final class TraceLog {
    private TraceLog() {
    }

    static void info(Logger logger, String message, Object... arguments) {
        withTraceContext(() -> logger.info(message, arguments));
    }

    static void error(Logger logger, String message, Object... arguments) {
        withTraceContext(() -> logger.error(message, arguments));
    }

    private static void withTraceContext(Runnable logStatement) {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            logStatement.run();
            return;
        }

        try (MDC.MDCCloseable ignoredTrace = MDC.putCloseable("trace_id", context.getTraceId());
             MDC.MDCCloseable ignoredSpan = MDC.putCloseable("span_id", context.getSpanId())) {
            logStatement.run();
        }
    }
}
