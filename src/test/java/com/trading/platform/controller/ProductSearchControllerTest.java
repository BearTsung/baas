package com.trading.platform.controller;

import com.trading.platform.config.SecurityConfig;
import com.trading.platform.dto.CursorPage;
import com.trading.platform.dto.ProductResponse;
import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.exception.BadRequestException;
import com.trading.platform.security.JwtAuthenticationFilter;
import com.trading.platform.security.JwtUtil;
import com.trading.platform.service.OrderService;
import com.trading.platform.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 /api/products/search 的參數綁定、回應格式，以及不合法輸入對應到 400。
 */
@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class})
class ProductSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private OrderService orderService;

    private MockHttpServletRequestBuilder search() {
        return get("/api/products/search")
                .header("Authorization", "Bearer " + jwtUtil.generateToken("user", "USER"));
    }

    @Test
    @DisplayName("query string 綁定到 ProductSearchRequest")
    void bindsQueryParameters() throws Exception {
        when(productService.search(any())).thenReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(search()
                        .param("keyword", "鍵盤")
                        .param("minPrice", "100")
                        .param("maxPrice", "2999.99")
                        .param("sort", "price")
                        .param("direction", "asc")
                        .param("size", "10")
                        .param("cursor", "abc"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductSearchRequest> captor = ArgumentCaptor.forClass(ProductSearchRequest.class);
        verify(productService).search(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(new ProductSearchRequest("鍵盤", 100.0, 2999.99, "price", "asc", 10, "abc"));
    }

    @Test
    @DisplayName("回應包含 items、nextCursor、hasNext")
    void responseShape() throws Exception {
        ProductResponse keyboard = new ProductResponse(1L, "機械鍵盤", 2999.99, 10, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(productService.search(any())).thenReturn(new CursorPage<>(List.of(keyboard), "next-token", true));

        mockMvc.perform(search())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("機械鍵盤"))
                .andExpect(jsonPath("$.items[0].price").value(2999.99))
                .andExpect(jsonPath("$.nextCursor").value("next-token"))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("service 判定參數不合法時回傳 400 與錯誤訊息")
    void badRequestException_mapsTo400() throws Exception {
        when(productService.search(any())).thenThrow(new BadRequestException("size 需介於 1 到 100"));

        mockMvc.perform(search().param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("size 需介於 1 到 100"));
    }

    @Test
    @DisplayName("價格不是數字時回傳 400，不會呼叫 service")
    void nonNumericPrice_is400() throws Exception {
        mockMvc.perform(search().param("minPrice", "abc"))
                .andExpect(status().isBadRequest());
        verify(productService, never()).search(any());
    }
}
