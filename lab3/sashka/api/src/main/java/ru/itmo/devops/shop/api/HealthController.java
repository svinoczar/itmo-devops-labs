package ru.itmo.devops.shop.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final boolean healthFail;

    public HealthController(@Value("${health.fail:false}") boolean healthFail) {
        this.healthFail = healthFail;
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        if (healthFail) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unhealthy"));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
