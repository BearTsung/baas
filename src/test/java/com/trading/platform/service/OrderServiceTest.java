package com.trading.platform.service;

import com.trading.platform.dto.OrderRequest;
import com.trading.platform.entity.Order;
import com.trading.platform.entity.Product;
import com.trading.platform.entity.User;
import com.trading.platform.repository.OrderRepository;
import com.trading.platform.repository.ProductRepository;
import com.trading.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long PRODUCT_ID = 10L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderService orderService;

    private User alice;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setRole("USER");

        keyboard = new Product();
        keyboard.setId(PRODUCT_ID);
        keyboard.setName("機械鍵盤");
        keyboard.setPrice(2999.99);
        keyboard.setStock(8);
    }

    private OrderRequest request(Long productId, Integer quantity) {
        OrderRequest request = new OrderRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        return request;
    }

    @Test
    @DisplayName("下單成功：扣庫存後建立訂單，並正確計算總價")
    void placeOrder_success() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(productRepository.decreaseStock(2, PRODUCT_ID)).thenReturn(1);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(keyboard));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        Order result = orderService.placeOrder("alice", request(PRODUCT_ID, 2));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(alice);
        assertThat(saved.getProduct()).isSameAs(keyboard);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getTotalPrice()).isEqualTo(5999.98);
        assertThat(result.getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("總價以 BigDecimal 計算，不會因浮點數或整數轉型失真")
    void placeOrder_totalPriceKeepsDecimals() {
        keyboard.setPrice(0.1);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(productRepository.decreaseStock(3, PRODUCT_ID)).thenReturn(1);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(keyboard));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.placeOrder("alice", request(PRODUCT_ID, 3));

        // 0.1 * 3 以 double 計算會得到 0.30000000000000004
        assertThat(result.getTotalPrice()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("先以條件式 UPDATE 扣庫存，成功後才讀取商品")
    void placeOrder_decreasesStockBeforeLoadingProduct() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(productRepository.decreaseStock(1, PRODUCT_ID)).thenReturn(1);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(keyboard));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.placeOrder("alice", request(PRODUCT_ID, 1));

        InOrder order = inOrder(productRepository, orderRepository);
        order.verify(productRepository).decreaseStock(1, PRODUCT_ID);
        order.verify(productRepository).findById(PRODUCT_ID);
        order.verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("庫存不足：條件式 UPDATE 影響 0 筆時拒絕下單，不建立訂單")
    void placeOrder_insufficientStock() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(productRepository.decreaseStock(99, PRODUCT_ID)).thenReturn(0);

        assertThatThrownBy(() -> orderService.placeOrder("alice", request(PRODUCT_ID, 99)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("庫存不足");

        verify(productRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("使用者不存在：拒絕下單，不扣庫存")
    void placeOrder_userNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder("ghost", request(PRODUCT_ID, 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("使用者不存在");

        verify(productRepository, never()).decreaseStock(anyInt(), any());
        verify(orderRepository, never()).save(any());
    }

    @ParameterizedTest(name = "quantity = {0}")
    @NullSource
    @ValueSource(ints = {0, -1})
    @DisplayName("數量不合法（null、0、負數）：拒絕下單，不扣庫存")
    void placeOrder_invalidQuantity(Integer quantity) {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertThatThrownBy(() -> orderService.placeOrder("alice", request(PRODUCT_ID, quantity)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("訂單資料有誤");

        verify(productRepository, never()).decreaseStock(anyInt(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("查詢訂單：以 equals 比對使用者 id，id 超過 Long 快取範圍（>127）也能正確比對")
    void getUserOrders_comparesIdsByValue() {
        // 刻意使用不同的 Long 物件，舊的 == 比對會判定為不相等
        Long bigId = 1000L;
        User user = new User();
        user.setId(bigId);
        user.setUsername("bob");

        User sameUserFromOrder = new User();
        sameUserFromOrder.setId(Long.valueOf(1000L));
        sameUserFromOrder.setUsername("bob");

        User otherUser = new User();
        otherUser.setId(2000L);

        Order mine = new Order();
        mine.setUser(sameUserFromOrder);
        mine.setProduct(keyboard);

        Order others = new Order();
        others.setUser(otherUser);
        others.setProduct(keyboard);

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(orderRepository.findByUserId(bigId)).thenReturn(List.of(mine, others));

        List<Order> result = orderService.getUserOrders("bob");

        assertThat(sameUserFromOrder.getId()).isNotSameAs(user.getId());
        assertThat(result).containsExactly(mine);
    }
}
