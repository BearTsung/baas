package com.trading.platform.service;

import com.trading.platform.dto.CursorPage;
import com.trading.platform.dto.ProductResponse;
import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以真實 PostgreSQL（Testcontainers）驗證商品進階查詢，未啟動 Docker 時自動略過。
 * 資料表由 Hibernate ddl-auto 建立，再以 @Sql 套用與正式環境相同的索引腳本。
 * 每個測試的商品名稱都帶唯一前綴，並以該前綴作為關鍵字，避免與其他測試的資料互相干擾。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Sql(scripts = "classpath:db/product-search-indexes.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ProductSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private Product createProduct(String name, double price) {
        return createProduct(name, price, LocalDateTime.now());
    }

    private Product createProduct(String name, double price, LocalDateTime createdAt) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(price);
        product.setStock(1);
        product.setCreatedAt(createdAt);
        return productRepository.save(product);
    }

    private static ProductSearchRequest keyword(String keyword) {
        return new ProductSearchRequest(keyword, null, null, null, null, null, null);
    }

    private List<String> searchNames(String keyword) {
        return productService.search(keyword(keyword)).items().stream().map(ProductResponse::name).toList();
    }

    /** 依 nextCursor 一頁一頁取完所有結果 */
    private List<ProductResponse> fetchAllPages(ProductSearchRequest first) {
        List<ProductResponse> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CursorPage<ProductResponse> page = productService.search(new ProductSearchRequest(
                    first.keyword(), first.minPrice(), first.maxPrice(),
                    first.sort(), first.direction(), first.size(), cursor));
            assertThat(page.items()).hasSizeLessThanOrEqualTo(first.size());
            assertThat(page.hasNext()).isEqualTo(page.nextCursor() != null);
            all.addAll(page.items());
            cursor = page.nextCursor();
            assertThat(++pages).as("分頁沒有結束，可能陷入循環").isLessThan(100);
        } while (cursor != null);
        return all;
    }

    // ---- 模糊搜尋 ----

    @Test
    @DisplayName("部分比對且不分大小寫")
    void keyword_containingIgnoreCase() {
        createProduct(tag + " Mechanical KEYBOARD", 100);
        createProduct(tag + " Monitor", 100);

        assertThat(searchNames(tag + " mechanical keyboard")).containsExactly(tag + " Mechanical KEYBOARD");
        assertThat(searchNames(tag.toUpperCase() + " MONITOR")).containsExactly(tag + " Monitor");
    }

    @Test
    @DisplayName("JPQL injection 字串被當成一般文字，不會撈出全部商品")
    void keyword_injectionPayload_isTreatedAsLiteral() {
        createProduct(tag + " 機械鍵盤", 100);

        // 若以字串拼接，會變成 LIKE '%zzz' OR p.id > 0 OR p.name = '%'，撈出全部商品
        assertThat(searchNames("zzz' OR p.id > 0 OR p.name = '")).isEmpty();
    }

    @Test
    @DisplayName("含單引號的關鍵字可正常查詢")
    void keyword_singleQuote_matchesLiterally() {
        createProduct(tag + " Kid's Chair", 100);

        assertThat(searchNames(tag + " Kid's")).containsExactly(tag + " Kid's Chair");
    }

    @Test
    @DisplayName("% 不會被當成萬用字元")
    void keyword_percent_isEscaped() {
        createProduct(tag + " 100% 純棉", 100);
        createProduct(tag + " 一般商品", 100);

        assertThat(searchNames(tag + " 100%")).containsExactly(tag + " 100% 純棉");
        // 若 % 未跳脫，"tag%商品" 會匹配 "tag 一般商品"
        assertThat(searchNames(tag + "%商品")).isEmpty();
    }

    @Test
    @DisplayName("_ 不會被當成單一字元萬用字元")
    void keyword_underscore_isEscaped() {
        createProduct(tag + "_A", 100);
        createProduct(tag + "-B", 100);

        assertThat(searchNames(tag + "_")).containsExactly(tag + "_A");
    }

    @Test
    @DisplayName("反斜線被當成一般文字")
    void keyword_backslash_isEscaped() {
        createProduct(tag + " C:\\path", 100);

        assertThat(searchNames(tag + " C:\\path")).containsExactly(tag + " C:\\path");
    }

    // ---- 價格區間 ----

    @Test
    @DisplayName("價格區間包含上下界，可只給其中一邊")
    void priceRange_inclusiveBounds() {
        for (double price : new double[]{99.99, 100, 250, 500, 500.01}) {
            createProduct(tag + " " + price, price);
        }

        assertThat(prices(new ProductSearchRequest(tag, 100.0, 500.0, "price", "asc", 20, null)))
                .containsExactly(100.0, 250.0, 500.0);
        assertThat(prices(new ProductSearchRequest(tag, 500.0, null, "price", "asc", 20, null)))
                .containsExactly(500.0, 500.01);
        assertThat(prices(new ProductSearchRequest(tag, null, 100.0, "price", "asc", 20, null)))
                .containsExactly(99.99, 100.0);
    }

    @Test
    @DisplayName("關鍵字與價格區間同時生效")
    void keywordAndPriceRange_combined() {
        createProduct(tag + " 鍵盤 A", 1000);
        createProduct(tag + " 鍵盤 B", 3000);
        createProduct(tag + " 滑鼠", 1000);

        List<String> names = productService.search(new ProductSearchRequest(tag + " 鍵盤", 500.0, 2000.0, null, null, null, null))
                .items().stream().map(ProductResponse::name).toList();

        assertThat(names).containsExactly(tag + " 鍵盤 A");
    }

    private List<Double> prices(ProductSearchRequest request) {
        return productService.search(request).items().stream().map(ProductResponse::price).toList();
    }

    // ---- 排序 + keyset 分頁 ----

    @ParameterizedTest(name = "sort={0}, direction={1}")
    @CsvSource({"price,asc", "price,desc", "createdAt,asc", "createdAt,desc", "id,asc", "id,desc"})
    @DisplayName("逐頁取完的結果與一次排序全部資料完全一致：不重複、不遺漏，同值時依 id 排序")
    void keysetPagination_traversesAllRowsInOrder(String sort, String direction) {
        // 價格與建立時間刻意大量重複，驗證同值時的分頁邊界
        LocalDateTime base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        List<Product> created = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            created.add(createProduct(tag + " item" + i, 100.0 * (i % 4), base.plusMinutes(i % 3)));
        }

        List<ProductResponse> paged = fetchAllPages(new ProductSearchRequest(tag, null, null, sort, direction, 5, null));

        Comparator<ProductResponse> order = switch (sort) {
            case "price" -> Comparator.comparingDouble(ProductResponse::price).thenComparing(ProductResponse::id);
            case "createdAt" -> Comparator.comparing(ProductResponse::createdAt).thenComparing(ProductResponse::id);
            default -> Comparator.comparing(ProductResponse::id);
        };
        if (direction.equals("desc")) {
            order = order.reversed();
        }
        List<Long> expected = paged.stream().sorted(order).map(ProductResponse::id).toList();

        assertThat(paged).extracting(ProductResponse::id)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(created.stream().map(Product::getId).toList())
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("最後一頁剛好填滿時 hasNext 為 false")
    void lastPageExactlyFull_hasNoNext() {
        for (int i = 0; i < 4; i++) {
            createProduct(tag + " item" + i, 100);
        }

        CursorPage<ProductResponse> first = productService.search(new ProductSearchRequest(tag, null, null, "id", "asc", 2, null));
        CursorPage<ProductResponse> second = productService.search(new ProductSearchRequest(tag, null, null, "id", "asc", 2, first.nextCursor()));

        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).hasSize(2);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("翻頁途中新增排在前面的資料，下一頁不會重複出現已看過的商品")
    void insertDuringPaging_doesNotShiftNextPage() {
        for (int i = 1; i <= 4; i++) {
            createProduct(tag + " item" + i, 100.0 * i);
        }
        CursorPage<ProductResponse> first = productService.search(new ProductSearchRequest(tag, null, null, "price", "asc", 2, null));

        createProduct(tag + " cheaper", 50);  // 若用 OFFSET，這筆會把 item2 擠到第二頁而重複出現
        CursorPage<ProductResponse> second = productService.search(new ProductSearchRequest(tag, null, null, "price", "asc", 2, first.nextCursor()));

        assertThat(first.items()).extracting(ProductResponse::price).containsExactly(100.0, 200.0);
        assertThat(second.items()).extracting(ProductResponse::price).containsExactly(300.0, 400.0);
    }
}
