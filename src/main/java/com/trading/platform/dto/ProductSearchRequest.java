package com.trading.platform.dto;

import io.swagger.v3.oas.annotations.Parameter;

/**
 * 商品進階查詢的 query string 參數；檢查與預設值由 ProductSearchCriteria 處理。
 */
public record ProductSearchRequest(
        @Parameter(description = "名稱模糊搜尋（不分大小寫），最多 100 字")
        String keyword,
        @Parameter(description = "最低價格（含）")
        Double minPrice,
        @Parameter(description = "最高價格（含）")
        Double maxPrice,
        @Parameter(description = "排序欄位：price、createdAt、id，預設 createdAt")
        String sort,
        @Parameter(description = "排序方向：asc、desc，預設 desc")
        String direction,
        @Parameter(description = "每頁筆數 1–100，預設 20")
        Integer size,
        @Parameter(description = "上一頁回應的 nextCursor，第一頁不帶")
        String cursor
) {
}
