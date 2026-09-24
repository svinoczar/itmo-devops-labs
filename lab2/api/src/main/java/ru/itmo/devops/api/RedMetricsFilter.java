package ru.itmo.devops.api;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@Component
public class RedMetricsFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedMetricsFilter.class);

    private final MeterRegistry registry;

    public RedMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startedAt;
            String route = routeOf(request);
            String status = Integer.toString(response.getStatus());

            Counter.builder("api.http.requests")
                    .description("Total number of HTTP requests")
                    .tags("method", request.getMethod(), "route", route, "status", status)
                    .register(registry)
                    .increment();
            Timer.builder("api.http.request.duration")
                    .description("HTTP request duration")
                    .tags("method", request.getMethod(), "route", route)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(
                            Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
                            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                            Duration.ofSeconds(1), Duration.ofMillis(1500), Duration.ofSeconds(2),
                            Duration.ofMillis(2500), Duration.ofSeconds(3), Duration.ofSeconds(5)
                    )
                    .register(registry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
            if (response.getStatus() >= 500) {
                Counter.builder("api.http.request.errors")
                        .description("Total number of HTTP 5xx responses")
                        .tags("method", request.getMethod(), "route", route, "status", status)
                        .register(registry)
                        .increment();
            }

            TraceLog.info(
                    LOGGER,
                    "Request completed {}, {}, {}, {}",
                    keyValue("method", request.getMethod()),
                    keyValue("route", route),
                    keyValue("status_code", response.getStatus()),
                    keyValue("duration_ms", Math.round(durationNanos / 10_000.0) / 100.0)
            );
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/metrics".equals(request.getRequestURI());
    }

    private String routeOf(HttpServletRequest request) {
        Object routePattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return routePattern == null ? request.getRequestURI() : routePattern.toString();
    }
}
