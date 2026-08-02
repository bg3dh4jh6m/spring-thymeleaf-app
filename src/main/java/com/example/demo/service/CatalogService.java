package com.example.demo.service;

import com.example.demo.catalog.CatalogProduct;
import com.example.demo.catalog.CatalogProductRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class CatalogService {

    private static final String PHOTO_CATALOG_SOURCE = "PHOTO_CATALOG_2026-08-02";
    private final CatalogProductRepository productsRepository;

    public CatalogService(CatalogProductRepository productsRepository) {
        this.productsRepository = productsRepository;
    }

    public List<CatalogProduct> getAllProducts() {
        return productsRepository.findAllByOrderByCategoryAscNameAsc();
    }

    @Transactional
    public CatalogProduct createProduct(String name, String productType, String category,
                                        String baseUnit, String packageOptions, String notes) {
        String cleanName = required(name, "Името е задължително.");
        String normalizedName = normalizeName(cleanName);
        if (productsRepository.findFirstByNormalizedName(normalizedName).isPresent()) {
            throw new IllegalArgumentException("В каталога вече има продукт с това име.");
        }

        Instant now = Instant.now();
        CatalogProduct product = new CatalogProduct();
        product.setName(cleanName);
        product.setNormalizedName(normalizedName);
        product.setProductType(normalizeProductType(productType));
        product.setCategory(defaultIfBlank(category, "Други"));
        product.setBaseUnit(defaultIfBlank(baseUnit, "бр"));
        product.setPackageOptions(clean(packageOptions));
        product.setNotes(clean(notes));
        product.setSource("MANUAL");
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        return saveWithInternalCode(product);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedPhotoCatalog() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("catalog/photo-catalog.txt").getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(this::seedLine);
        } catch (Exception e) {
            throw new IllegalStateException("Неуспешно начално зареждане на продуктовия каталог", e);
        }
    }

    private void seedLine(String line) {
        String[] columns = line.split("\\|", -1);
        if (columns.length != 4) {
            throw new IllegalArgumentException("Невалиден каталожен ред: " + line);
        }
        String category = columns[0].strip();
        String name = columns[1].strip();
        String baseUnit = columns[2].strip();
        String packageOptions = columns[3].strip();
        String normalizedName = normalizeName(name);
        CatalogProduct existing = productsRepository.findFirstByNormalizedName(normalizedName).orElse(null);
        if (existing != null) {
            enrichExisting(existing, category, baseUnit, packageOptions);
            return;
        }

        Instant now = Instant.now();
        CatalogProduct product = new CatalogProduct();
        product.setName(name);
        product.setNormalizedName(normalizedName);
        product.setProductType("INGREDIENT");
        product.setCategory(category);
        product.setBaseUnit(baseUnit);
        product.setPackageOptions(packageOptions);
        product.setSource(PHOTO_CATALOG_SOURCE);
        product.setNotes("Добавен от предоставения продуктов лист.");
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        saveWithInternalCode(product);
    }

    private void enrichExisting(CatalogProduct product, String category, String baseUnit, String packageOptions) {
        boolean changed = false;
        if (product.getProductType() == null || product.getProductType().isBlank()) {
            product.setProductType("INGREDIENT");
            changed = true;
        }
        if (product.getCategory() == null || product.getCategory().isBlank()
                || product.getCategory().equalsIgnoreCase("Подправки")) {
            product.setCategory(category);
            changed = true;
        }
        if (product.getBaseUnit() == null || product.getBaseUnit().isBlank()
                || product.getBaseUnit().equals("бр")) {
            product.setBaseUnit(baseUnit);
            changed = true;
        }
        if (product.getPackageOptions() == null || product.getPackageOptions().isBlank()) {
            product.setPackageOptions(packageOptions);
            changed = true;
        }
        if (product.getSource() == null || product.getSource().isBlank()) {
            product.setSource("SUPPLIER");
            changed = true;
        }
        if (changed) {
            product.setUpdatedAt(Instant.now());
            productsRepository.save(product);
        }
    }

    private CatalogProduct saveWithInternalCode(CatalogProduct product) {
        CatalogProduct saved = productsRepository.save(product);
        if (saved.getInternalCode() == null || saved.getInternalCode().isBlank()) {
            saved.setInternalCode("HMG-%06d".formatted(saved.getId()));
            saved = productsRepository.save(saved);
        }
        return saved;
    }

    public String normalizeName(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .toLowerCase(new Locale("bg"))
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private String normalizeProductType(String value) {
        return switch (Objects.toString(value, "").toUpperCase(Locale.ROOT)) {
            case "PACKAGING" -> "PACKAGING";
            case "CONSUMABLE" -> "CONSUMABLE";
            case "OTHER" -> "OTHER";
            default -> "INGREDIENT";
        };
    }

    private String required(String value, String message) {
        String result = clean(value);
        if (result.isBlank()) throw new IllegalArgumentException(message);
        return result;
    }

    private String defaultIfBlank(String value, String fallback) {
        String result = clean(value);
        return result.isBlank() ? fallback : result;
    }

    private String clean(String value) {
        return Objects.toString(value, "").replaceAll("\\s+", " ").strip();
    }
}
