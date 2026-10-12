package ru.itmo.devops.shop.api;

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

    private static void withTraceContext(Runnable statement) {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            statement.run();
            return;
        }
        try (MDC.MDCCloseable trace = MDC.putCloseable("trace_id", context.getTraceId());
             MDC.MDCCloseable span = MDC.putCloseable("span_id", context.getSpanId())) {
            statement.run();
        }
    }
}
