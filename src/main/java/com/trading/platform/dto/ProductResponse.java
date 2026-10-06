package com.trading.platform.dto;

import com.trading.platform.entity.Product;

import java.time.LocalDateTime;

public record ProductResponse(Long id, String name, double price, Integer stock, LocalDateTime createdAt) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getCreatedAt());
    }
}
