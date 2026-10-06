package com.trading.platform.service.search;

import com.trading.platform.entity.Product;
import com.trading.platform.exception.BadRequestException;
import org.springframework.data.domain.Sort;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * keyset 分頁的位置：上一頁最後一筆的 (排序值, id)。
 * 編碼為 Base64URL 的 "排序欄位|方向|id|排序值"，排序值放最後，因為時間格式本身含有冒號等字元。
 */
public record ProductSearchCursor(ProductSortField sort, Sort.Direction direction, long id, Comparable<?> value) {

    public static ProductSearchCursor of(Product last, ProductSortField sort, Sort.Direction direction) {
        return new ProductSearchCursor(sort, direction, last.getId(), sort.valueOf(last));
    }

    public String encode() {
        String raw = sort.property() + "|" + direction.name() + "|" + id + "|" + (value == null ? "" : value.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 cursor，並確認它是以相同的排序條件產生的；否則下一頁的定位會錯亂。
     */
    public static ProductSearchCursor decode(String token, ProductSortField sort, Sort.Direction direction) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", 4);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("cursor 格式錯誤");
        }
        if (parts.length != 4) {
            throw new BadRequestException("cursor 格式錯誤");
        }
        if (!parts[0].equals(sort.property()) || !parts[1].equals(direction.name())) {
            throw new BadRequestException("cursor 與目前的排序條件不符，請從第一頁重新查詢");
        }
        try {
            return new ProductSearchCursor(sort, direction, Long.parseLong(parts[2]), sort.parseValue(parts[3]));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BadRequestException("cursor 格式錯誤");
        }
    }
}
