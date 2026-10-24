package ru.itmo.devops.shop.api;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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

@Component
public class RedMetricsFilter extends OncePerRequestFilter {
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
            long duration = System.nanoTime() - startedAt;
            String route = routeOf(request);
            String status = Integer.toString(response.getStatus());

            Counter.builder("shop.api.http.requests")
                    .tags("method", request.getMethod(), "route", route, "status", status)
                    .register(registry)
                    .increment();
            Timer.builder("shop.api.http.request.duration")
                    .tags("method", request.getMethod(), "route", route)
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(
                            Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(100),
                            Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
                            Duration.ofSeconds(2), Duration.ofSeconds(5)
                    )
                    .register(registry)
                    .record(duration, TimeUnit.NANOSECONDS);
            if (response.getStatus() >= 500) {
                Counter.builder("shop.api.http.request.errors")
                        .tags("method", request.getMethod(), "route", route, "status", status)
                        .register(registry)
                        .increment();
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/metrics".equals(request.getRequestURI());
    }

    private String routeOf(HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return route == null ? request.getRequestURI() : route.toString();
    }
}
