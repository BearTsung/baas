package com.trading.platform.repository;

import com.trading.platform.entity.Product;
import com.trading.platform.service.search.ProductSearchCriteria;
import com.trading.platform.service.search.ProductSearchCursor;
import com.trading.platform.service.search.ProductSortField;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> matching(ProductSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.keyword() != null) {
                // 需與 idx_products_name_trgm 的 lower(name) 一致；跳脫 % _ \ 讓使用者輸入只當作一般文字
                String pattern = "%" + escapeLike(criteria.keyword().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
            }
            if (criteria.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), criteria.minPrice()));
            }
            if (criteria.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("price"), criteria.maxPrice()));
            }
            if (criteria.cursor() != null) {
                predicates.add(after(root, cb, criteria.cursor()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * keyset 條件：(排序值, id) 位於 cursor 之後，即 v > c OR (v = c AND id > cid)。
     * 寫成 v >= c AND (v > c OR id > cid)，前半段讓 PostgreSQL 能直接用 (欄位, id) 索引定位起點，
     * 不必從頭掃描再過濾（那樣會和 OFFSET 一樣越後面越慢）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate after(Root<Product> root, CriteriaBuilder cb, ProductSearchCursor cursor) {
        Path<Long> id = root.get("id");
        boolean asc = cursor.direction().isAscending();
        Predicate idAfter = asc ? cb.greaterThan(id, cursor.id()) : cb.lessThan(id, cursor.id());
        if (cursor.sort() == ProductSortField.ID) {
            return idAfter;
        }
        Path path = root.get(cursor.sort().property());
        Comparable value = cursor.value();
        return asc
                ? cb.and(cb.greaterThanOrEqualTo(path, value), cb.or(cb.greaterThan(path, value), idAfter))
                : cb.and(cb.lessThanOrEqualTo(path, value), cb.or(cb.lessThan(path, value), idAfter));
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
