package com.trading.platform.service;

import com.trading.platform.dto.OrderRequest;
import com.trading.platform.entity.Order;
import com.trading.platform.entity.Product;
import com.trading.platform.entity.User;
import com.trading.platform.repository.OrderRepository;
import com.trading.platform.repository.ProductRepository;
import com.trading.platform.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 以真實 PostgreSQL（Testcontainers）驗證下單的併發與交易行為，未啟動 Docker 時自動略過。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    private Product createProduct(int stock) {
        Product product = new Product();
        product.setName("測試商品-" + UUID.randomUUID());
        product.setPrice(100.0);
        product.setStock(stock);
        return productRepository.save(product);
    }

    private User createUser() {
        User user = new User();
        user.setUsername("buyer-" + UUID.randomUUID());
        user.setPassword("password");
        user.setRole("USER");
        return userRepository.save(user);
    }

    private OrderRequest request(Long productId, int quantity) {
        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        return request;
    }

    @Test
    @DisplayName("併發搶購：成功筆數剛好等於庫存，庫存歸零且不為負數")
    void concurrentOrders_doNotOversell() throws Exception {
        int stock = 5;
        int buyers = 30;
        Product product = createProduct(stock);
        User user = createUser();

        ExecutorService executor = Executors.newFixedThreadPool(buyers);
        CountDownLatch ready = new CountDownLatch(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < buyers; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    orderService.placeOrder(user.getUsername(), request(product.getId(), 1));
                    return true;
                } catch (RuntimeException e) {
                    assertThat(e).hasMessage("庫存不足");
                    return false;
                }
            }));
        }

        // 所有執行緒就緒後同時放行，盡量製造競爭
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();

        int success = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        executor.shutdown();

        assertThat(success).isEqualTo(stock);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isZero();
        assertThat(orderRepository.findByUserId(user.getId())).hasSize(stock);
    }

    @Test
    @DisplayName("併發下多件：總扣減量不超過庫存")
    void concurrentOrdersWithMultipleQuantity_doNotOversell() throws Exception {
        int stock = 10;
        int buyers = 20;
        int quantityPerOrder = 3;
        Product product = createProduct(stock);
        User user = createUser();

        ExecutorService executor = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        for (int i = 0; i < buyers; i++) {
            results.add(executor.submit(() -> {
                start.await();
                try {
                    orderService.placeOrder(user.getUsername(), request(product.getId(), quantityPerOrder));
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();

        int success = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        executor.shutdown();

        // 10 個庫存每次買 3 個，最多只能成功 3 筆，剩 1 個
        assertThat(success).isEqualTo(stock / quantityPerOrder);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(stock % quantityPerOrder);
    }

    @Test
    @DisplayName("建立訂單失敗時，已扣減的庫存會一併回滾")
    void saveOrderFails_stockIsRolledBack() {
        Product product = createProduct(5);
        User user = createUser();
        doThrow(new RuntimeException("模擬寫入訂單失敗")).when(orderRepository).save(any(Order.class));

        assertThatThrownBy(() -> orderService.placeOrder(user.getUsername(), request(product.getId(), 2)))
                .hasMessage("模擬寫入訂單失敗");

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
    }
}
