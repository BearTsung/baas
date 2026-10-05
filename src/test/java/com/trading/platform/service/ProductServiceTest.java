package com.trading.platform.service;

import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    @DisplayName("searchByName：null 或空白關鍵字直接回傳空清單，不查資料庫")
    void searchByName_blankKeyword_returnsEmpty(String keyword) {
        assertThat(productService.searchByName(keyword)).isEmpty();
        verify(productRepository, never()).findByNameContainingIgnoreCase(anyString());
    }

    @Test
    @DisplayName("searchByName：關鍵字超過 100 字拋出例外")
    void searchByName_tooLongKeyword_throws() {
        assertThatThrownBy(() -> productService.searchByName("a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("keyword too long");
        verify(productRepository, never()).findByNameContainingIgnoreCase(anyString());
    }

    @Test
    @DisplayName("searchByName：剛好 100 字仍可查詢")
    void searchByName_maxLengthKeyword_isAllowed() {
        String keyword = "a".repeat(100);
        when(productRepository.findByNameContainingIgnoreCase(keyword)).thenReturn(List.of());

        assertThat(productService.searchByName(keyword)).isEmpty();
        verify(productRepository).findByNameContainingIgnoreCase(keyword);
    }

    @Test
    @DisplayName("searchByName：去除前後空白後交給 repository 查詢")
    void searchByName_trimsKeyword() {
        Product keyboard = new Product();
        keyboard.setName("機械鍵盤");
        when(productRepository.findByNameContainingIgnoreCase("鍵盤")).thenReturn(List.of(keyboard));

        assertThat(productService.searchByName("  鍵盤  ")).containsExactly(keyboard);
    }
}
