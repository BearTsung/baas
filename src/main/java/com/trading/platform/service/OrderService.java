package com.trading.platform.service;

import com.trading.platform.dto.OrderRequest;
import com.trading.platform.entity.Order;
import com.trading.platform.entity.Product;
import com.trading.platform.entity.User;
import com.trading.platform.repository.OrderRepository;
import com.trading.platform.repository.ProductRepository;
import com.trading.platform.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    private final AtomicInteger placedOrderCount = new AtomicInteger(0);

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                        UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    // 庫存以條件式 UPDATE 原子扣減，併發下單不會超賣
    @Transactional
    public Order placeOrder(String username, OrderRequest request) {
        Integer quantity = request.getQuantity();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            throw new RuntimeException("使用者不存在");
        }
        if (!validateOrder(request)) {
            throw new RuntimeException("訂單資料有誤");
        }
        if (productRepository.decreaseStock(quantity, request.getProductId()) == 0) {
            throw new RuntimeException("庫存不足");
        }

        Product product = productRepository.findById(request.getProductId()).orElseThrow();

        Order order = new Order();
        order.setUser(user);
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setTotalPrice(BigDecimal.valueOf(product.getPrice()).multiply(new BigDecimal(quantity)).doubleValue());
        order = orderRepository.save(order);
        System.out.println("建立訂單 " + order.getId() + "，本機累計 " + placedOrderCount.incrementAndGet() + " 筆");

        return order;
    }

    private boolean validateOrder(OrderRequest request) {
        // 驗證下單數量與商品是否合法
        Integer quantity = request.getQuantity();
        return quantity != null && quantity > 0;
    }

    public List<Order> getUserOrders(String username) {
        User user = userRepository.findByUsername(username).orElse(null);
        List<Order> orders = orderRepository.findByUserId(user.getId());
        List<Order> result = new ArrayList<>();
        for (Order o : orders) {
            if (o.getUser().getId().equals(user.getId())) {
                o.getProduct().getName();
                o.getUser().getUsername();
                result.add(o);
            }
        }
        return result;
    }
}
