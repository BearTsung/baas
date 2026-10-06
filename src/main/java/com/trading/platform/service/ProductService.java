package com.trading.platform.service;

import com.trading.platform.dto.CursorPage;
import com.trading.platform.dto.ProductRequest;
import com.trading.platform.dto.ProductResponse;
import com.trading.platform.dto.ProductSearchRequest;
import com.trading.platform.entity.Product;
import com.trading.platform.repository.ProductRepository;
import com.trading.platform.repository.ProductSpecifications;
import com.trading.platform.service.search.ProductSearchCriteria;
import com.trading.platform.service.search.ProductSearchCursor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product createProduct(ProductRequest request) {
        Product p = new Product();
        p.setName(request.name());
        p.setPrice(request.price());
        p.setStock(request.stock());
        return productRepository.save(p);
    }

    public Product updateProduct(Long id, ProductRequest request) {
        Product p = productRepository.findById(id).get();
        p.setName(request.name());
        p.setPrice(request.price());
        p.setStock(request.stock());
        return productRepository.save(p);
    }

    public void deleteProduct(Long id) {
        productRepository.deleteById(id);
    }

    public List<Product> listProducts() {
        return productRepository.findAll();
    }

    public Product getProduct(Long id) {
        return productRepository.findById(id).orElse(null);
    }

    /**
     * 模糊搜尋 + 價格區間 + 排序 + keyset 分頁。
     * 多取一筆用來判斷是否還有下一頁，不另外計算總筆數。
     */
    public CursorPage<ProductResponse> search(ProductSearchRequest request) {
        ProductSearchCriteria criteria = ProductSearchCriteria.from(request);
        List<Product> rows = productRepository.findBy(
                ProductSpecifications.matching(criteria),
                query -> query.sortBy(criteria.toSort()).limit(criteria.size() + 1).all());

        boolean hasNext = rows.size() > criteria.size();
        List<Product> page = hasNext ? rows.subList(0, criteria.size()) : rows;
        String nextCursor = hasNext
                ? ProductSearchCursor.of(page.getLast(), criteria.sort(), criteria.direction()).encode()
                : null;
        return new CursorPage<>(page.stream().map(ProductResponse::from).toList(), nextCursor, hasNext);
    }
}
