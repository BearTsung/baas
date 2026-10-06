package com.trading.platform.service.search;

import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.exception.BadRequestException;
import org.springframework.data.domain.Sort;

/**
 * 經過檢查與套用預設值後的查詢條件。
 */
public record ProductSearchCriteria(
        String keyword,
        Double minPrice,
        Double maxPrice,
        ProductSortField sort,
        Sort.Direction direction,
        int size,
        ProductSearchCursor cursor
) {

    static final int MAX_KEYWORD_LENGTH = 100;
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    public static ProductSearchCriteria from(ProductSearchRequest request) {
        String keyword = request.keyword() == null || request.keyword().isBlank() ? null : request.keyword().trim();
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new BadRequestException("keyword 不可超過 " + MAX_KEYWORD_LENGTH + " 字");
        }

        checkPrice("minPrice", request.minPrice());
        checkPrice("maxPrice", request.maxPrice());
        if (request.minPrice() != null && request.maxPrice() != null && request.minPrice() > request.maxPrice()) {
            throw new BadRequestException("minPrice 不可大於 maxPrice");
        }

        int size = request.size() == null ? DEFAULT_SIZE : request.size();
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("size 需介於 1 到 " + MAX_SIZE);
        }

        ProductSortField sort = request.sort() == null || request.sort().isBlank()
                ? ProductSortField.CREATED_AT
                : ProductSortField.from(request.sort());
        Sort.Direction direction = request.direction() == null || request.direction().isBlank()
                ? Sort.Direction.DESC
                : Sort.Direction.fromOptionalString(request.direction())
                        .orElseThrow(() -> new BadRequestException("direction 只能是 asc 或 desc"));

        ProductSearchCursor cursor = request.cursor() == null || request.cursor().isBlank()
                ? null
                : ProductSearchCursor.decode(request.cursor(), sort, direction);

        return new ProductSearchCriteria(keyword, request.minPrice(), request.maxPrice(), sort, direction, size, cursor);
    }

    private static void checkPrice(String name, Double price) {
        if (price != null && (!Double.isFinite(price) || price < 0)) {
            throw new BadRequestException(name + " 必須是大於等於 0 的數字");
        }
    }

    /**
     * 排序最後一律加上 id，價格或時間相同的資料才有固定順序，翻頁時不會重複或遺漏。
     */
    public Sort toSort() {
        return sort == ProductSortField.ID
                ? Sort.by(direction, "id")
                : Sort.by(direction, sort.property(), "id");
    }
}
