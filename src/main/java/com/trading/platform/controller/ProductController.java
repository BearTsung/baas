package com.trading.platform.controller;

import com.trading.platform.dto.CursorPage;
import com.trading.platform.dto.ProductRequest;
import com.trading.platform.dto.ProductResponse;
import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.entity.Product;
import com.trading.platform.service.ProductService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Product create(@RequestBody ProductRequest request) {
        return productService.createProduct(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Product update(@PathVariable Long id, @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request);
    }

    @DeleteMapping("/delete/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return "deleted";
    }

    @GetMapping
    public List<Product> list() {
        return productService.listProducts();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable Long id) {
        return productService.getProduct(id);
    }

    @GetMapping("/search")
    public CursorPage<ProductResponse> search(@ParameterObject @ModelAttribute ProductSearchRequest request) {
        return productService.search(request);
    }
}
