package ru.itmo.devops.shop.worker;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
public class OrderWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderWorker.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("shop-worker");

    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;
    private final Counter processedCounter;
    private final Counter failureCounter;
    private final Timer batchTimer;

    public OrderWorker(
            JdbcTemplate jdbcTemplate,
            MeterRegistry registry,
            @Value("${worker.batch-size:10}") int batchSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = batchSize;
        this.processedCounter = registry.counter("shop.worker.orders.processed");
        this.failureCounter = registry.counter("shop.worker.processing.errors");
        this.batchTimer = registry.timer("shop.worker.batch.duration");
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:2000}")
    public void processOrders() {
        Span span = TRACER.spanBuilder("worker.process-orders").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            batchTimer.record(this::claimAndProcess);
        } catch (RuntimeException exception) {
            failureCounter.increment();
            span.recordException(exception);
            span.setStatus(StatusCode.ERROR, "Order processing failed");
            TraceLog.error(LOGGER, "Order processing failed", exception);
        } finally {
            span.end();
        }
    }

    private void claimAndProcess() {
        List<Long> processedIds = jdbcTemplate.queryForList("""
                WITH claimed AS (
                    SELECT id
                    FROM orders
                    WHERE status = 'PENDING'
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE orders AS target
                SET status = 'PROCESSED', processed_at = NOW()
                FROM claimed
                WHERE target.id = claimed.id
                RETURNING target.id
                """, Long.class, batchSize);

        if (processedIds.isEmpty()) {
            return;
        }

        processedCounter.increment(processedIds.size());
        Span.current().setAttribute("orders.processed", processedIds.size());
        TraceLog.info(
                LOGGER,
                "Orders processed {}, {}",
                keyValue("count", processedIds.size()),
                keyValue("order_ids", processedIds)
        );
    }
}
