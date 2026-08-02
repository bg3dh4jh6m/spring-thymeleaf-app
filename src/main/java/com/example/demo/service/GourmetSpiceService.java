package com.example.demo.service;

import com.example.demo.catalog.CatalogPriceHistory;
import com.example.demo.catalog.CatalogPriceHistoryRepository;
import com.example.demo.catalog.CatalogProduct;
import com.example.demo.catalog.CatalogProductRepository;
import com.example.demo.catalog.SupplierProduct;
import com.example.demo.catalog.SupplierProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class GourmetSpiceService {

    private static final String SUPPLIER = "gourmetspice";
    private static final String API = "https://gourmetspice.bg/wp-json/wc/store/v1/products";
    private static final List<Integer> RELEVANT_CATEGORY_IDS = List.of(66, 67, 68, 69, 71);
    private static final Set<Integer> RELEVANT_CATEGORY_SET = Set.copyOf(RELEVANT_CATEGORY_IDS);
    private static final long REFRESH_INTERVAL_MS = Duration.ofMinutes(15).toMillis();
    private static final long EMPTY_RETRY_INTERVAL_MS = Duration.ofSeconds(20).toMillis();
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Sofia"));

    private final CatalogProductRepository catalogProductsRepository;
    private final SupplierProductRepository supplierProductsRepository;
    private final CatalogPriceHistoryRepository historyRepository;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile List<Map<String, String>> cachedProducts;
    private volatile long lastRefreshStartedAt;
    private volatile String lastError = "";

    public GourmetSpiceService(CatalogProductRepository catalogProductsRepository,
                               SupplierProductRepository supplierProductsRepository,
                               CatalogPriceHistoryRepository historyRepository,
                               ObjectMapper json) {
        this.catalogProductsRepository = catalogProductsRepository;
        this.supplierProductsRepository = supplierProductsRepository;
        this.historyRepository = historyRepository;
        this.json = json;
        this.cachedProducts = loadDatabaseCache();
    }

    /** Returns the database snapshot immediately and refreshes the supplier in the background. */
    public List<Map<String, String>> getCachedProductsAndRefresh() {
        long now = System.currentTimeMillis();
        long interval = cachedProducts.isEmpty() ? EMPTY_RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
        if (now - lastRefreshStartedAt >= interval && refreshing.compareAndSet(false, true)) {
            lastRefreshStartedAt = now;
            CompletableFuture.runAsync(this::refreshWhileLocked);
        }
        return cachedProducts;
    }

    /** Runs a supplier scan now, unless another Gourmet Spice scan is already active. */
    public void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) return;
        lastRefreshStartedAt = System.currentTimeMillis();
        refreshWhileLocked();
    }

    private void refreshWhileLocked() {
        try {
            List<Map<String, String>> fresh = fetchRelevantCatalog();
            if (!fresh.isEmpty()) {
                persistSnapshot(fresh);
                cachedProducts = List.copyOf(fresh);
                lastError = "";
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "неизвестна грешка");
        } finally {
            refreshing.set(false);
        }
    }

    public boolean isRefreshing() { return refreshing.get(); }
    public String getLastError() { return lastError; }

    private List<Map<String, String>> fetchRelevantCatalog() {
        List<CompletableFuture<JsonNode>> requests = RELEVANT_CATEGORY_IDS.stream()
                .map(categoryId -> fetchCategory(categoryId))
                .toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        Map<Long, JsonNode> unique = new LinkedHashMap<>();
        requests.forEach(request -> request.join().forEach(product -> unique.put(product.path("id").asLong(), product)));
        Instant capturedAt = Instant.now();
        return unique.values().stream()
                .map(product -> mapProduct(product, capturedAt))
                .sorted(Comparator.comparing(p -> p.get("name"), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private CompletableFuture<JsonNode> fetchCategory(int categoryId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "?category=" + categoryId + "&per_page=100"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "SpiceCatalog/1.0")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Gourmet Spice API върна HTTP " + response.statusCode());
                    }
                    try {
                        return json.readTree(response.body());
                    } catch (Exception e) {
                        throw new IllegalStateException("Невалиден отговор от Gourmet Spice", e);
                    }
                });
    }

    private Map<String, String> mapProduct(JsonNode product, Instant capturedAt) {
        long externalId = product.path("id").asLong();
        JsonNode prices = product.path("prices");
        int minorUnit = prices.path("currency_minor_unit").asInt(2);
        JsonNode range = prices.path("price_range");
        String minRaw = range.path("min_amount").asText(prices.path("price").asText("0"));
        String maxRaw = range.path("max_amount").asText(prices.path("price").asText("0"));
        BigDecimal minPrice = minorAmount(minRaw, minorUnit);
        BigDecimal maxPrice = minorAmount(maxRaw, minorUnit);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("supplierCode", "GS-%06d".formatted(externalId));
        result.put("externalId", Long.toString(externalId));
        result.put("name", cleanHtml(product.path("name").asText("")));
        result.put("category", relevantCategories(product.path("categories")));
        result.put("packageOptions", packageOptions(product.path("attributes")));
        result.put("price", priceLabel(minPrice, maxPrice));
        result.put("priceMin", minPrice.toPlainString());
        result.put("priceMax", maxPrice.toPlainString());
        result.put("productUrl", product.path("permalink").asText(""));
        result.put("imageUrl", firstImage(product.path("images")));
        result.put("addedAt", DISPLAY_TIME.format(capturedAt));
        return result;
    }

    private String relevantCategories(JsonNode categories) {
        List<String> names = new ArrayList<>();
        categories.forEach(category -> {
            if (RELEVANT_CATEGORY_SET.contains(category.path("id").asInt())) {
                names.add(cleanHtml(category.path("name").asText("")));
            }
        });
        return String.join(", ", names);
    }

    private String packageOptions(JsonNode attributes) {
        List<String> options = new ArrayList<>();
        attributes.forEach(attribute -> {
            String name = cleanHtml(attribute.path("name").asText(""));
            if (name.equalsIgnoreCase("Тегло") || name.equalsIgnoreCase("Разфасовка")) {
                attribute.path("terms").forEach(term -> options.add(cleanHtml(term.path("name").asText(""))));
            }
        });
        return options.stream().filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(", "));
    }

    private String firstImage(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) return "";
        JsonNode image = images.get(0);
        String thumbnail = image.path("thumbnail").asText("");
        return thumbnail.isBlank() ? image.path("src").asText("") : thumbnail;
    }

    private BigDecimal minorAmount(String raw, int minorUnit) {
        try {
            return new BigDecimal(raw).movePointLeft(minorUnit).setScale(2);
        } catch (Exception ignored) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    private String priceLabel(BigDecimal min, BigDecimal max) {
        return min.compareTo(max) == 0 ? "€" + min : "€" + min + " – €" + max;
    }

    private String cleanHtml(String value) {
        return Jsoup.parse(value).text().strip();
    }

    private List<Map<String, String>> loadDatabaseCache() {
        try {
            return supplierProductsRepository.findBySupplierOrderBySupplierNameAsc(SUPPLIER).stream().map(this::toMap).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void persistSnapshot(List<Map<String, String>> products) {
        List<String> codes = products.stream().map(p -> p.get("supplierCode")).toList();
        Map<String, SupplierProduct> existing = supplierProductsRepository.findAllById(codes).stream()
                .collect(Collectors.toMap(SupplierProduct::getSupplierCode, product -> product));
        Instant capturedAt = Instant.now();
        List<SupplierProduct> current = new ArrayList<>();
        List<CatalogPriceHistory> history = new ArrayList<>();

        for (Map<String, String> source : products) {
            String code = source.get("supplierCode");
            BigDecimal min = new BigDecimal(source.get("priceMin"));
            BigDecimal max = new BigDecimal(source.get("priceMax"));
            SupplierProduct product = existing.get(code);
            String matchMethod = "EXISTING_LINK";
            if (product == null) {
                product = new SupplierProduct(code);
                String normalizedName = normalizeName(source.get("name"));
                CatalogProduct master = catalogProductsRepository.findFirstByNormalizedName(normalizedName).orElse(null);
                if (master == null) {
                    master = new CatalogProduct();
                    master.setName(source.get("name"));
                    master.setNormalizedName(normalizedName);
                    master.setCategory(source.get("category"));
                    master.setBaseUnit("бр");
                    master.setCreatedAt(capturedAt);
                    master.setUpdatedAt(capturedAt);
                    master = catalogProductsRepository.save(master);
                    master.setInternalCode("HMG-%06d".formatted(master.getId()));
                    master = catalogProductsRepository.save(master);
                    matchMethod = "NEW_MASTER";
                } else {
                    matchMethod = "EXACT_NAME";
                }
                product.setCatalogProduct(master);
            }
            if (product.getPriceMinEur() == null || product.getPriceMaxEur() == null
                    || product.getPriceMinEur().compareTo(min) != 0 || product.getPriceMaxEur().compareTo(max) != 0) {
                history.add(new CatalogPriceHistory(code, min, max, capturedAt));
            }
            product.setSupplier(SUPPLIER);
            product.setExternalId(source.get("externalId"));
            product.setSupplierName(source.get("name"));
            product.setCategory(source.get("category"));
            product.setPackageOptions(source.get("packageOptions"));
            product.setPriceMinEur(min);
            product.setPriceMaxEur(max);
            product.setProductUrl(source.get("productUrl"));
            product.setImageUrl(source.get("imageUrl"));
            product.setMatchMethod(matchMethod);
            product.setCapturedAt(capturedAt);
            source.put("catalogCode", product.getCatalogProduct().getInternalCode());
            current.add(product);
        }
        supplierProductsRepository.saveAll(current);
        historyRepository.saveAll(history);
    }

    private String normalizeName(String name) {
        return Normalizer.normalize(Objects.toString(name, ""), Normalizer.Form.NFKC)
                .toLowerCase(new java.util.Locale("bg"))
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private Map<String, String> toMap(SupplierProduct product) {
        Map<String, String> result = new HashMap<>();
        result.put("catalogCode", product.getCatalogProduct().getInternalCode());
        result.put("supplierCode", product.getSupplierCode());
        result.put("externalId", product.getExternalId());
        result.put("name", product.getSupplierName());
        result.put("category", Objects.toString(product.getCategory(), ""));
        result.put("packageOptions", Objects.toString(product.getPackageOptions(), ""));
        result.put("priceMin", product.getPriceMinEur().toPlainString());
        result.put("priceMax", product.getPriceMaxEur().toPlainString());
        result.put("price", priceLabel(product.getPriceMinEur(), product.getPriceMaxEur()));
        result.put("productUrl", Objects.toString(product.getProductUrl(), ""));
        result.put("imageUrl", Objects.toString(product.getImageUrl(), ""));
        result.put("addedAt", DISPLAY_TIME.format(product.getCapturedAt()));
        return result;
    }
}
