package com.trading.platform.service.search;

import com.trading.platform.entity.Product;
import com.trading.platform.exception.BadRequestException;

import java.time.LocalDateTime;

/**
 * 可排序欄位白名單，每個欄位都有對應的 (欄位, id) 索引。
 */
public enum ProductSortField {

    PRICE("price") {
        @Override
        Comparable<?> valueOf(Product product) {
            return product.getPrice();
        }

        @Override
        Comparable<?> parseValue(String value) {
            return Double.valueOf(value);
        }
    },
    CREATED_AT("createdAt") {
        @Override
        Comparable<?> valueOf(Product product) {
            return product.getCreatedAt();
        }

        @Override
        Comparable<?> parseValue(String value) {
            return LocalDateTime.parse(value);
        }
    },
    // 以主鍵排序時 cursor 只需要 id
    ID("id") {
        @Override
        Comparable<?> valueOf(Product product) {
            return null;
        }

        @Override
        Comparable<?> parseValue(String value) {
            return null;
        }
    };

    private final String property;

    ProductSortField(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }

    abstract Comparable<?> valueOf(Product product);

    abstract Comparable<?> parseValue(String value);

    public static ProductSortField from(String property) {
        for (ProductSortField field : values()) {
            if (field.property.equals(property)) {
                return field;
            }
        }
        throw new BadRequestException("sort 只能是 price、createdAt、id");
    }
}
