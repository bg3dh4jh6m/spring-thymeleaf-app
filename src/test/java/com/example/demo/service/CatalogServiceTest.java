package com.example.demo.service;

import com.example.demo.catalog.CatalogProduct;
import com.example.demo.catalog.CatalogProductRepository;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    @Test
    void createsPackagingWithStableHoremagCode() throws Exception {
        CatalogProductRepository repository = mock(CatalogProductRepository.class);
        CatalogService service = new CatalogService(repository);
        when(repository.findFirstByNormalizedName("стъклено бурканче 200 мл")).thenReturn(Optional.empty());
        Field id = CatalogProduct.class.getDeclaredField("id");
        id.setAccessible(true);
        when(repository.save(any(CatalogProduct.class))).thenAnswer(invocation -> {
            CatalogProduct product = invocation.getArgument(0);
            if (product.getId() == null) id.set(product, 42L);
            return product;
        });

        CatalogProduct result = service.createProduct(
                "Стъклено бурканче 200 мл", "PACKAGING", "Бурканчета", "бр", "200 мл", "Прозрачно");

        assertThat(result.getInternalCode()).isEqualTo("HMG-000042");
        assertThat(result.getProductType()).isEqualTo("PACKAGING");
        assertThat(result.getSource()).isEqualTo("MANUAL");
        assertThat(result.getPackageOptions()).isEqualTo("200 мл");
    }

    @Test
    void rejectsDuplicateManualProduct() {
        CatalogProductRepository repository = mock(CatalogProductRepository.class);
        CatalogService service = new CatalogService(repository);
        when(repository.findFirstByNormalizedName("манатарка сушена"))
                .thenReturn(Optional.of(new CatalogProduct()));

        assertThatThrownBy(() -> service.createProduct(
                "Манатарка сушена", "INGREDIENT", "Сушени гъби", "кг", "50 г", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("вече има продукт");
    }

    @Test
    void photoCatalogHasUniqueStructuredProductsAndMushrooms() throws Exception {
        CatalogService service = new CatalogService(mock(CatalogProductRepository.class));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/catalog/photo-catalog.txt"), StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .toList();
            HashSet<String> names = new HashSet<>();
            long mushrooms = 0;
            for (String line : lines) {
                String[] columns = line.split("\\|", -1);
                assertThat(columns).hasSize(4);
                assertThat(names.add(service.normalizeName(columns[1]))).isTrue();
                if (columns[0].equals("Сушени гъби")) mushrooms++;
            }
            assertThat(lines).hasSizeGreaterThanOrEqualTo(230);
            assertThat(mushrooms).isEqualTo(11);
        }
    }
}
