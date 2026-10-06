package com.trading.platform.service.search;

import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.entity.Product;
import com.trading.platform.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSearchCriteriaTest {

    private static ProductSearchRequest request(String keyword, Double min, Double max,
                                                String sort, String direction, Integer size, String cursor) {
        return new ProductSearchRequest(keyword, min, max, sort, direction, size, cursor);
    }

    private static ProductSearchCriteria criteria(ProductSearchRequest request) {
        return ProductSearchCriteria.from(request);
    }

    // ---- 預設值 ----

    @Test
    @DisplayName("未帶任何參數：不篩選、依 createdAt desc 排序、每頁 20 筆")
    void defaults() {
        ProductSearchCriteria c = criteria(request(null, null, null, null, null, null, null));

        assertThat(c.keyword()).isNull();
        assertThat(c.minPrice()).isNull();
        assertThat(c.maxPrice()).isNull();
        assertThat(c.sort()).isEqualTo(ProductSortField.CREATED_AT);
        assertThat(c.direction()).isEqualTo(Sort.Direction.DESC);
        assertThat(c.size()).isEqualTo(20);
        assertThat(c.cursor()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("空白關鍵字視為不篩選名稱")
    void blankKeyword_isIgnored(String keyword) {
        assertThat(criteria(request(keyword, null, null, null, null, null, null)).keyword()).isNull();
    }

    @Test
    @DisplayName("關鍵字去除前後空白")
    void keyword_isTrimmed() {
        assertThat(criteria(request("  鍵盤  ", null, null, null, null, null, null)).keyword()).isEqualTo("鍵盤");
    }

    @Test
    @DisplayName("排序條件一律以 id 作為次要排序")
    void toSort_appendsIdAsTieBreaker() {
        assertThat(criteria(request(null, null, null, "price", "asc", null, null)).toSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "price", "id"));
        assertThat(criteria(request(null, null, null, "id", "desc", null, null)).toSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
    }

    // ---- 不合法的輸入 ----

    @Test
    @DisplayName("關鍵字剛好 100 字可以，超過則拒絕")
    void keyword_lengthLimit() {
        assertThat(criteria(request("a".repeat(100), null, null, null, null, null, null)).keyword()).hasSize(100);
        assertThatThrownBy(() -> criteria(request("a".repeat(101), null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("價格不可為負數、NaN 或無限大")
    void price_mustBeFiniteAndNonNegative() {
        assertThatThrownBy(() -> criteria(request(null, -1.0, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("minPrice");
        assertThatThrownBy(() -> criteria(request(null, null, Double.NaN, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("maxPrice");
        assertThatThrownBy(() -> criteria(request(null, null, Double.POSITIVE_INFINITY, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("minPrice 大於 maxPrice 被拒絕，相等則可以")
    void priceRange_minMustNotExceedMax() {
        assertThatThrownBy(() -> criteria(request(null, 500.0, 100.0, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThat(criteria(request(null, 100.0, 100.0, null, null, null, null)).minPrice()).isEqualTo(100.0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101})
    @DisplayName("size 超出 1–100 被拒絕")
    void size_outOfRange(int size) {
        assertThatThrownBy(() -> criteria(request(null, null, null, null, null, size, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "stock", "price; drop table products", "PRICE"})
    @DisplayName("白名單以外的排序欄位被拒絕")
    void sort_notInWhitelist(String sort) {
        assertThatThrownBy(() -> criteria(request(null, null, null, sort, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("排序方向只接受 asc / desc（不分大小寫）")
    void direction_parsing() {
        assertThat(criteria(request(null, null, null, null, "ASC", null, null)).direction()).isEqualTo(Sort.Direction.ASC);
        assertThatThrownBy(() -> criteria(request(null, null, null, null, "up", null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- cursor ----

    @Test
    @DisplayName("cursor 編碼後可還原出相同的位置（價格）")
    void cursor_roundTrip_price() {
        Product last = product(123L, 2999.99, LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000));
        String token = ProductSearchCursor.of(last, ProductSortField.PRICE, Sort.Direction.ASC).encode();

        ProductSearchCursor cursor = criteria(request(null, null, null, "price", "asc", null, token)).cursor();

        assertThat(cursor.id()).isEqualTo(123L);
        assertThat(cursor.value()).isEqualTo(2999.99);
    }

    @Test
    @DisplayName("cursor 編碼後可還原出相同的位置（建立時間，含微秒）")
    void cursor_roundTrip_createdAt() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
        String token = ProductSearchCursor.of(product(7L, 1.0, createdAt), ProductSortField.CREATED_AT, Sort.Direction.DESC).encode();

        ProductSearchCursor cursor = criteria(request(null, null, null, null, null, null, token)).cursor();

        assertThat(cursor.id()).isEqualTo(7L);
        assertThat(cursor.value()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("cursor 與目前排序條件不同時被拒絕")
    void cursor_sortMismatch() {
        String token = ProductSearchCursor.of(product(1L, 10.0, LocalDateTime.now()), ProductSortField.PRICE, Sort.Direction.ASC).encode();

        assertThatThrownBy(() -> criteria(request(null, null, null, "price", "desc", null, token)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("排序條件");
        assertThatThrownBy(() -> criteria(request(null, null, null, "createdAt", "asc", null, token)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("排序條件");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!not-base64!!!", "cHJpY2U", "price|ASC|abc|1.0", "price|ASC|1|not-a-number"})
    @DisplayName("格式錯誤或被竄改的 cursor 回傳 BadRequest 而非 500")
    void cursor_malformed(String raw) {
        String token = raw.startsWith("price|")
                ? Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))
                : raw;

        assertThatThrownBy(() -> criteria(request(null, null, null, "price", "asc", null, token)))
                .isInstanceOf(BadRequestException.class);
    }

    private static Product product(Long id, double price, LocalDateTime createdAt) {
        Product product = new Product();
        product.setId(id);
        product.setPrice(price);
        product.setCreatedAt(createdAt);
        return product;
    }
}
