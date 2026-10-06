package com.trading.platform.dto;

import java.util.List;

/**
 * keyset 分頁結果：以 nextCursor 取下一頁，不提供總筆數（百萬級資料計算 count 成本過高）。
 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasNext) {
}
