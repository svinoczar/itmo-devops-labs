package ru.itmo.devops.shop.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderRequest(
        @NotBlank @Size(max = 255) String item,
        @Min(1) @Max(10_000) int quantity
) {
}
