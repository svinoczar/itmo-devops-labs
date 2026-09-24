package ru.itmo.devops.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@RestController
public class ApiController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("lab2-api");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final String selfUrl;

    public ApiController(@Value("${self.url:http://127.0.0.1:8080}") String selfUrl) {
        this.selfUrl = selfUrl.replaceAll("/+$", "");
    }

    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }

    @GetMapping("/fail")
    public ResponseEntity<Map<String, String>> fail() {
        Span.current().setStatus(StatusCode.ERROR, "Deliberate test failure");
        Span.current().setAttribute("error.type", "deliberate_test_failure");
        TraceLog.error(
                LOGGER,
                "Deliberate failure requested {}, {}",
                keyValue("route", "/fail"),
                keyValue("status_code", 500)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "deliberate test failure"));
    }

    @GetMapping("/slow")
    public Map<String, Double> slow() throws InterruptedException {
        double delaySeconds = ThreadLocalRandom.current().nextDouble(1.0, 3.0);
        Span slowSpan = TRACER.spanBuilder("slow-op").startSpan();
        try (Scope ignored = slowSpan.makeCurrent()) {
            slowSpan.setAttribute("slow.duration_seconds", delaySeconds);
            Thread.sleep((long) (delaySeconds * 1_000));
        } catch (InterruptedException exception) {
            slowSpan.recordException(exception);
            slowSpan.setStatus(StatusCode.ERROR);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            slowSpan.end();
        }
        return Map.of("slept_seconds", Math.round(delaySeconds * 1_000.0) / 1_000.0);
    }

    @GetMapping("/load")
    public Map<String, Integer> load(@RequestParam(defaultValue = "50") int requests) {
        if (requests < 1 || requests > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requests must be between 1 and 200");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(selfUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        List<CompletableFuture<HttpResponse<Void>>> calls = IntStream.range(0, requests)
                .mapToObj(ignored -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()))
                .toList();

        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        int succeeded = (int) calls.stream()
                .map(CompletableFuture::join)
                .filter(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                .count();
        return Map.of("requested", requests, "succeeded", succeeded, "failed", requests - succeeded);
    }
}
