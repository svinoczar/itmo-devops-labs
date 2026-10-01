package ru.itmo.devops.shop.api;

import java.time.OffsetDateTime;

public record Order(
        long id,
        String item,
        int quantity,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime processedAt
) {
}
