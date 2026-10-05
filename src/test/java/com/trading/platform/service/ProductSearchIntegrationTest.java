package com.trading.platform.service;

import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 以真實 PostgreSQL（Testcontainers）驗證商品搜尋不受 JPQL injection 與 LIKE 萬用字元影響，未啟動 Docker 時自動略過。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProductSearchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    // 每個測試用唯一前綴，避免與其他測試的資料互相干擾
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private Product createProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(100.0);
        product.setStock(1);
        return productRepository.save(product);
    }

    private List<String> searchNames(String keyword) {
        return productService.searchByName(keyword).stream().map(Product::getName).toList();
    }

    @Test
    @DisplayName("部分比對且不分大小寫")
    void search_containingIgnoreCase() {
        createProduct(tag + " Mechanical KEYBOARD");
        createProduct(tag + " Monitor");

        assertThat(searchNames(tag + " mechanical keyboard")).containsExactly(tag + " Mechanical KEYBOARD");
        assertThat(searchNames("keyboard")).contains(tag + " Mechanical KEYBOARD")
                .doesNotContain(tag + " Monitor");
    }

    @Test
    @DisplayName("JPQL injection 字串被當成一般文字，不會撈出全部商品")
    void search_injectionPayload_isTreatedAsLiteral() {
        createProduct(tag + " 機械鍵盤");

        // 若以字串拼接，會變成 LIKE '%zzz' OR p.id > 0 OR p.name = '%'，撈出全部商品
        String payload = "zzz' OR p.id > 0 OR p.name = '";
        assertThatCode(() -> productService.searchByName(payload)).doesNotThrowAnyException();
        assertThat(productService.searchByName(payload)).isEmpty();
    }

    @Test
    @DisplayName("含單引號的關鍵字可正常查詢，不會造成語法錯誤")
    void search_singleQuote_matchesLiterally() {
        createProduct(tag + " Kid's Chair");

        assertThat(searchNames("Kid's Chair")).contains(tag + " Kid's Chair");
    }

    @Test
    @DisplayName("% 不會被當成萬用字元")
    void search_percent_isEscaped() {
        createProduct(tag + " 100% 純棉");
        createProduct(tag + " 一般商品");

        assertThat(searchNames(tag + " 100%")).containsExactly(tag + " 100% 純棉");
        // 若 % 未跳脫，"tag%商品" 會匹配 "tag 一般商品"
        assertThat(searchNames(tag + "%商品")).isEmpty();
    }

    @Test
    @DisplayName("_ 不會被當成單一字元萬用字元")
    void search_underscore_isEscaped() {
        createProduct(tag + "_A");
        createProduct(tag + "-B");

        assertThat(searchNames(tag + "_")).containsExactly(tag + "_A");
    }

    @Test
    @DisplayName("反斜線被當成一般文字")
    void search_backslash_isEscaped() {
        createProduct(tag + " C:\\path");

        assertThat(searchNames(tag + " C:\\path")).containsExactly(tag + " C:\\path");
    }
}
