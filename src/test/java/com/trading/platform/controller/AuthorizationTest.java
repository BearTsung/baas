package com.trading.platform.controller;

import com.trading.platform.config.SecurityConfig;
import com.trading.platform.dto.OrderRequest;
import com.trading.platform.dto.ProductRequest;
import com.trading.platform.entity.Order;
import com.trading.platform.entity.Product;
import com.trading.platform.security.JwtAuthenticationFilter;
import com.trading.platform.security.JwtUtil;
import com.trading.platform.service.OrderService;
import com.trading.platform.service.ProductService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 以真實 JWT 走完 JwtAuthenticationFilter → SecurityConfig → Controller，驗證商品與訂單 API 的角色權限。
 */
@WebMvcTest({ProductController.class, OrderController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class})
class AuthorizationTest {

    private static final String PRODUCT_JSON = """
            {"name":"機械鍵盤","price":2999.0,"stock":10}
            """;
    private static final String ORDER_JSON = """
            {"productId":1,"quantity":1}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private OrderService orderService;

    private MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwtUtil.generateToken(role.toLowerCase(), role));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- 未登入 ----

    @Test
    @DisplayName("未帶 token 存取商品列表被拒絕")
    void anonymous_listProducts_isRejected() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("偽造簽章的 token 被視為未登入")
    void forgedToken_isRejected() throws Exception {
        String forged = Jwts.builder()
                .subject("mallory")
                .claim("role", "ADMIN")
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(json(post("/api/products"), PRODUCT_JSON).header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden());
        verify(productService, never()).createProduct(any());
    }

    // ---- 一般使用者 ----

    @Test
    @DisplayName("USER 可以瀏覽商品列表、單一商品與搜尋")
    void user_canBrowseProducts() throws Exception {
        when(productService.listProducts()).thenReturn(List.of());
        when(productService.getProduct(1L)).thenReturn(new Product());
        when(productService.searchByName("鍵盤")).thenReturn(List.of());

        mockMvc.perform(as("USER", get("/api/products"))).andExpect(status().isOk());
        mockMvc.perform(as("USER", get("/api/products/1"))).andExpect(status().isOk());
        mockMvc.perform(as("USER", get("/api/products/search").param("keyword", "鍵盤"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 可以下單與查詢自己的訂單")
    void user_canPlaceAndListOrders() throws Exception {
        when(orderService.placeOrder(eq("user"), any(OrderRequest.class))).thenReturn(new Order());
        when(orderService.getUserOrders("user")).thenReturn(List.of());

        mockMvc.perform(as("USER", json(post("/api/orders"), ORDER_JSON))).andExpect(status().isOk());
        mockMvc.perform(as("USER", get("/api/orders"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("USER 不能新增商品")
    void user_cannotCreateProduct() throws Exception {
        mockMvc.perform(as("USER", json(post("/api/products"), PRODUCT_JSON)))
                .andExpect(status().isForbidden());
        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("USER 不能修改商品")
    void user_cannotUpdateProduct() throws Exception {
        mockMvc.perform(as("USER", json(put("/api/products/1"), PRODUCT_JSON)))
                .andExpect(status().isForbidden());
        verify(productService, never()).updateProduct(anyLong(), any());
    }

    @Test
    @DisplayName("USER 不能刪除商品")
    void user_cannotDeleteProduct() throws Exception {
        mockMvc.perform(as("USER", delete("/api/products/delete/1")))
                .andExpect(status().isForbidden());
        verify(productService, never()).deleteProduct(anyLong());
    }

    @Test
    @DisplayName("token 沒有 role claim 時可瀏覽但不能新增商品")
    void tokenWithoutRole_cannotCreateProduct() throws Exception {
        String token = jwtUtil.generateToken("legacy", null);

        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/api/products"), PRODUCT_JSON).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        verify(productService, never()).createProduct(any());
    }

    // ---- 管理員 ----

    @Test
    @DisplayName("ADMIN 可以新增商品")
    void admin_canCreateProduct() throws Exception {
        when(productService.createProduct(any(ProductRequest.class))).thenReturn(new Product());

        mockMvc.perform(as("ADMIN", json(post("/api/products"), PRODUCT_JSON)))
                .andExpect(status().isOk());
        verify(productService).createProduct(new ProductRequest("機械鍵盤", 2999.0, 10));
    }

    @Test
    @DisplayName("ADMIN 可以修改商品")
    void admin_canUpdateProduct() throws Exception {
        when(productService.updateProduct(eq(1L), any(ProductRequest.class))).thenReturn(new Product());

        mockMvc.perform(as("ADMIN", json(put("/api/products/1"), PRODUCT_JSON)))
                .andExpect(status().isOk());
        verify(productService).updateProduct(1L, new ProductRequest("機械鍵盤", 2999.0, 10));
    }

    @Test
    @DisplayName("ADMIN 可以刪除商品")
    void admin_canDeleteProduct() throws Exception {
        mockMvc.perform(as("ADMIN", delete("/api/products/delete/1")))
                .andExpect(status().isOk());
        verify(productService).deleteProduct(1L);
    }
}
