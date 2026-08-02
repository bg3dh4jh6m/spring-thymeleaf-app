package com.example.demo.service;

import com.example.demo.catalog.CatalogPriceHistory;
import com.example.demo.catalog.CatalogPriceHistoryRepository;
import com.example.demo.catalog.CatalogProduct;
import com.example.demo.catalog.CatalogProductRepository;
import com.example.demo.catalog.SupplierOffer;
import com.example.demo.catalog.SupplierOfferPriceHistory;
import com.example.demo.catalog.SupplierOfferPriceHistoryRepository;
import com.example.demo.catalog.SupplierOfferRepository;
import com.example.demo.catalog.SupplierProduct;
import com.example.demo.catalog.SupplierProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GourmetSpiceService {

    private static final String SUPPLIER = "gourmetspice";
    private static final String API = "https://gourmetspice.bg/wp-json/wc/store/v1/products";
    private static final List<Integer> RELEVANT_CATEGORY_IDS = List.of(66, 67, 68, 69, 71);
    private static final Set<Integer> RELEVANT_CATEGORY_SET = Set.copyOf(RELEVANT_CATEGORY_IDS);
    private static final long REFRESH_INTERVAL_MS = Duration.ofHours(6).toMillis();
    private static final long EMPTY_RETRY_INTERVAL_MS = Duration.ofSeconds(20).toMillis();
    private static final Pattern MEASURE_PATTERN = Pattern.compile("(?iu)(\\d+(?:[.,]\\d+)?)\\s*(kg|кг|g|гр|г|литра?|л|l|мл|ml)\\b");
    private static final Pattern DECIMAL_KG_TITLE = Pattern.compile("(?iu)\\b(0[.,]\\d{3})\\b");
    private static final ForkJoinPool VARIATION_POOL = new ForkJoinPool(12);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Sofia"));

    private final CatalogProductRepository catalogProductsRepository;
    private final SupplierProductRepository supplierProductsRepository;
    private final SupplierOfferRepository offersRepository;
    private final CatalogPriceHistoryRepository historyRepository;
    private final SupplierOfferPriceHistoryRepository offerHistoryRepository;
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
                               SupplierOfferRepository offersRepository,
                               CatalogPriceHistoryRepository historyRepository,
                               SupplierOfferPriceHistoryRepository offerHistoryRepository,
                               ObjectMapper json) {
        this.catalogProductsRepository = catalogProductsRepository;
        this.supplierProductsRepository = supplierProductsRepository;
        this.offersRepository = offersRepository;
        this.historyRepository = historyRepository;
        this.offerHistoryRepository = offerHistoryRepository;
        this.json = json;
        List<SupplierOffer> previouslyCached = offersRepository.findAllBySupplier(SUPPLIER);
        previouslyCached.forEach(offer -> {
            offer.setActive(true);
            if (offer.getPackageLabel() == null || offer.getPackageLabel().isBlank()) {
                String inferredPackage = inferPackageFromName(offer.getSupplierProduct().getSupplierName());
                if (!inferredPackage.isBlank()) {
                    offer.setPackageLabel(inferredPackage);
                    offer.setPackageGrams(packageGrams(inferredPackage));
                    offer.setPackageMilliliters(packageMilliliters(inferredPackage));
                }
            }
            if (offer.getTotalQuantityLabel() == null || offer.getTotalQuantityLabel().isBlank()) {
                offer.setTotalQuantityLabel(offer.getPackageLabel());
            }
            if (offer.getMeasureUnit() == null || offer.getMeasureUnit().isBlank()) {
                if (offer.getPackageMilliliters() != null && offer.getPackageMilliliters() > 0) {
                    offer.setMeasureUnit("л");
                } else if (offer.getPackageGrams() != null && offer.getPackageGrams() > 0) {
                    offer.setMeasureUnit("кг");
                }
            }
            if (offer.getPricePerUnitEur() == null && offer.getPriceEur() != null) {
                int units = Math.max(offer.getUnitsPerPackage(), 1);
                offer.setPricePerUnitEur(offer.getPriceEur()
                        .divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP));
            }
            if (offer.getPricePerKgEur() == null && offer.getPriceEur() != null
                    && offer.getPackageGrams() != null && offer.getPackageGrams() > 0) {
                offer.setPricePerKgEur(offer.getPriceEur().multiply(BigDecimal.valueOf(1000))
                        .divide(BigDecimal.valueOf(offer.getPackageGrams()), 2, RoundingMode.HALF_UP));
            }
            if (offer.getPricePerLiterEur() == null && offer.getPriceEur() != null
                    && offer.getPackageMilliliters() != null && offer.getPackageMilliliters() > 0) {
                offer.setPricePerLiterEur(offer.getPriceEur().multiply(BigDecimal.valueOf(1000))
                        .divide(BigDecimal.valueOf(offer.getPackageMilliliters()), 2, RoundingMode.HALF_UP));
            }
        });
        if (!previouslyCached.isEmpty()) offersRepository.saveAll(previouslyCached);
        this.cachedProducts = loadDatabaseCache();
        // Older cache rows predate the normalized measure and per-unit columns.
        // Keep serving them immediately, but allow the first request to enrich
        // them in the background instead of treating them as fresh for six hours.
        boolean cacheUsesCurrentMeasureModel = this.cachedProducts.stream()
                .allMatch(product -> !product.getOrDefault("measureUnit", "").isBlank()
                        && !product.getOrDefault("pricePerUnit", "").isBlank());
        if (!this.cachedProducts.isEmpty() && cacheUsesCurrentMeasureModel) {
            this.lastRefreshStartedAt = System.currentTimeMillis();
        }
    }

    /** Returns exact cached package offers immediately and refreshes them in the background. */
    public List<Map<String, String>> getCachedProductsAndRefresh() {
        long now = System.currentTimeMillis();
        long interval = cachedProducts.isEmpty() ? EMPTY_RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
        if (now - lastRefreshStartedAt >= interval && refreshing.compareAndSet(false, true)) {
            lastRefreshStartedAt = now;
            CompletableFuture.runAsync(this::refreshWhileLocked);
        }
        return cachedProducts;
    }

    public void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) return;
        lastRefreshStartedAt = System.currentTimeMillis();
        refreshWhileLocked();
    }

    private void refreshWhileLocked() {
        try {
            List<Map<String, String>> fresh = fetchRelevantCatalogOffers();
            if (!fresh.isEmpty()) {
                persistSnapshot(fresh);
                // Keep successfully cached offers if a single remote variation
                // is temporarily unavailable during this scan.
                cachedProducts = loadDatabaseCache();
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

    private List<Map<String, String>> fetchRelevantCatalogOffers() {
        List<CompletableFuture<JsonNode>> requests = RELEVANT_CATEGORY_IDS.stream()
                .map(this::fetchCategory)
                .toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        Map<Long, JsonNode> unique = new LinkedHashMap<>();
        requests.forEach(request -> request.join().forEach(product -> unique.put(product.path("id").asLong(), product)));
        Instant capturedAt = Instant.now();
        boolean publishProgress = cachedProducts.isEmpty();
        List<Map<String, String>> progressive = Collections.synchronizedList(new ArrayList<>());
        VARIATION_POOL.submit(() -> unique.values().parallelStream().forEach(product -> {
            List<Map<String, String>> offers = expandProductOffers(product, capturedAt);
            progressive.addAll(offers);
            if (publishProgress && !progressive.isEmpty()) cachedProducts = List.copyOf(progressive);
        })).join();
        return progressive.stream()
                .sorted(Comparator.comparing((Map<String, String> row) -> row.get("name"), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(row -> Integer.parseInt(row.getOrDefault("packageBaseQuantity", "0"))))
                .toList();
    }

    private List<Map<String, String>> expandProductOffers(JsonNode product, Instant capturedAt) {
        List<JsonNode> variations = new ArrayList<>();
        product.path("variations").forEach(variations::add);
        if (variations.isEmpty()) {
            String packageLabel = inferSimplePackage(product);
            return List.of(mapOffer(product, product, null, packageLabel, capturedAt));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode reference : variations) {
            try {
                JsonNode variation = fetchProduct(reference.path("id").asLong());
                rows.add(mapOffer(product, variation, reference, variationPackage(reference), capturedAt));
            } catch (Exception ignored) {
                // One temporarily unavailable variation must not discard the rest of the catalog.
            }
        }
        return rows;
    }

    private Map<String, String> mapOffer(JsonNode product, JsonNode pricedNode, JsonNode variationReference,
                                         String packageLabel, Instant capturedAt) {
        long productId = product.path("id").asLong();
        long variationId = variationReference == null ? 0 : variationReference.path("id").asLong();
        JsonNode prices = pricedNode.path("prices");
        BigDecimal price = minorAmount(prices.path("price").asText("0"), prices.path("currency_minor_unit").asInt(2));
        int packageGrams = packageGrams(packageLabel);
        int packageMilliliters = packageMilliliters(packageLabel);
        String measureUnit = packageMilliliters > 0 ? "л" : packageGrams > 0 ? "кг" : "";
        BigDecimal pricePerKg = packageGrams > 0
                ? price.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(packageGrams), 2, RoundingMode.HALF_UP)
                : null;
        BigDecimal pricePerLiter = packageMilliliters > 0
                ? price.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(packageMilliliters), 2, RoundingMode.HALF_UP)
                : null;

        Map<String, String> result = new LinkedHashMap<>();
        result.put("supplierProductCode", "GS-%06d".formatted(productId));
        result.put("supplierCode", variationId == 0
                ? "GS-%06d-P".formatted(productId)
                : "GS-%06d-V%06d".formatted(productId, variationId));
        result.put("externalId", Long.toString(productId));
        result.put("variantExternalId", variationId == 0 ? Long.toString(productId) : Long.toString(variationId));
        result.put("name", cleanHtml(product.path("name").asText("")));
        result.put("category", relevantCategories(product.path("categories")));
        result.put("packageWeight", packageLabel);
        result.put("totalQuantity", packageLabel);
        result.put("packageGrams", Integer.toString(packageGrams));
        result.put("packageMilliliters", Integer.toString(packageMilliliters));
        result.put("packageBaseQuantity", Integer.toString(Math.max(packageGrams, packageMilliliters)));
        result.put("measureUnit", measureUnit);
        result.put("unitsPerPackage", "1 бр.");
        result.put("price", "€" + price);
        result.put("priceMin", price.toPlainString());
        result.put("pricePerUnit", "€" + price + "/бр.");
        result.put("pricePerKg", pricePerKg == null ? "" : "€" + pricePerKg + "/кг");
        result.put("pricePerLiter", pricePerLiter == null ? "" : "€" + pricePerLiter + "/л");
        result.put("pricePerMeasure", pricePerLiter == null
                ? result.get("pricePerKg")
                : result.get("pricePerLiter"));
        result.put("productUrl", product.path("permalink").asText(""));
        result.put("imageUrl", firstImage(product.path("images")));
        result.put("addedAt", DISPLAY_TIME.format(capturedAt));
        return result;
    }

    private CompletableFuture<JsonNode> fetchCategory(int categoryId) {
        HttpRequest request = request(API + "?category=" + categoryId + "&per_page=100");
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(this::parseResponse);
    }

    private JsonNode fetchProduct(long id) {
        try {
            return parseResponse(http.send(request(API + "/" + id), HttpResponse.BodyHandlers.ofString()));
        } catch (Exception e) {
            throw new IllegalStateException("Неуспешна вариация " + id, e);
        }
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "HoremagCatalog/1.0")
                .GET().build();
    }

    private JsonNode parseResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gourmet Spice API върна HTTP " + response.statusCode());
        }
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Невалиден отговор от Gourmet Spice", e);
        }
    }

    private String variationPackage(JsonNode reference) {
        for (JsonNode attribute : reference.path("attributes")) {
            String attributeName = cleanHtml(attribute.path("name").asText(""));
            if (attributeName.equalsIgnoreCase("Тегло") || attributeName.equalsIgnoreCase("Обем")) {
                String value = attribute.path("value").asText("");
                value = value.replaceFirst("^(\\d+)-(\\d+)-(kg|g|l|ml)$", "$1.$2 $3")
                        .replaceFirst("^(\\d+)-(kg|g|l|ml)$", "$1 $2");
                return value;
            }
        }
        return "";
    }

    private String inferSimplePackage(JsonNode product) {
        String fromName = inferPackageFromName(cleanHtml(product.path("name").asText("")));
        if (!fromName.isBlank()) return fromName;
        for (JsonNode attribute : product.path("attributes")) {
            String attributeName = cleanHtml(attribute.path("name").asText(""));
            if (!attributeName.equalsIgnoreCase("Тегло") && !attributeName.equalsIgnoreCase("Обем")) continue;
            JsonNode terms = attribute.path("terms");
            if (terms.size() == 1) return cleanHtml(terms.get(0).path("name").asText(""));
        }
        return "";
    }

    private String inferPackageFromName(String name) {
        Matcher explicitMeasure = MEASURE_PATTERN.matcher(Objects.toString(name, ""));
        if (explicitMeasure.find()) {
            return explicitMeasure.group(1).replace(',', '.') + " " + normalizeDisplayUnit(explicitMeasure.group(2));
        }
        // Gourmet Spice uses titles such as "0.250" and "0.100" for
        // kilogram quantities on a few simple products, without a unit suffix.
        Matcher decimalKilograms = DECIMAL_KG_TITLE.matcher(Objects.toString(name, ""));
        return decimalKilograms.find() ? decimalKilograms.group(1).replace(',', '.') + " kg" : "";
    }

    private int packageGrams(String label) {
        Matcher matcher = MEASURE_PATTERN.matcher(Objects.toString(label, ""));
        if (!matcher.find()) return 0;
        String unit = normalizeDisplayUnit(matcher.group(2));
        if (unit.equals("l") || unit.equals("ml")) return 0;
        BigDecimal value = new BigDecimal(matcher.group(1).replace(',', '.'));
        if (unit.equals("kg")) value = value.multiply(BigDecimal.valueOf(1000));
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private int packageMilliliters(String label) {
        Matcher matcher = MEASURE_PATTERN.matcher(Objects.toString(label, ""));
        if (!matcher.find()) return 0;
        String unit = normalizeDisplayUnit(matcher.group(2));
        if (!unit.equals("l") && !unit.equals("ml")) return 0;
        BigDecimal value = new BigDecimal(matcher.group(1).replace(',', '.'));
        if (unit.equals("l")) value = value.multiply(BigDecimal.valueOf(1000));
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private String normalizeDisplayUnit(String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT);
        if (normalized.equals("kg") || normalized.equals("кг")) return "kg";
        if (normalized.equals("ml") || normalized.equals("мл")) return "ml";
        if (normalized.equals("l") || normalized.equals("л") || normalized.startsWith("лит")) return "l";
        return "g";
    }

    private String relevantCategories(JsonNode categories) {
        List<String> names = new ArrayList<>();
        categories.forEach(category -> {
            if (RELEVANT_CATEGORY_SET.contains(category.path("id").asInt())) names.add(cleanHtml(category.path("name").asText("")));
        });
        return String.join(", ", names);
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

    private String cleanHtml(String value) { return Jsoup.parse(value).text().strip(); }

    private List<Map<String, String>> loadDatabaseCache() {
        try {
            return offersRepository.findActiveBySupplier(SUPPLIER).stream().map(this::toMap).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void persistSnapshot(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> groups = rows.stream()
                .collect(Collectors.groupingBy(row -> row.get("supplierProductCode"), LinkedHashMap::new, Collectors.toList()));
        Map<String, SupplierProduct> existingProducts = supplierProductsRepository.findAllById(groups.keySet()).stream()
                .collect(Collectors.toMap(SupplierProduct::getSupplierCode, Function.identity()));
        Instant capturedAt = Instant.now();
        List<SupplierProduct> supplierProducts = new ArrayList<>();
        List<CatalogPriceHistory> parentHistory = new ArrayList<>();

        for (Map.Entry<String, List<Map<String, String>>> group : groups.entrySet()) {
            Map<String, String> source = group.getValue().get(0);
            SupplierProduct product = existingProducts.get(group.getKey());
            String matchMethod = "EXISTING_LINK";
            if (product == null) {
                product = new SupplierProduct(group.getKey());
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

            BigDecimal min = group.getValue().stream().map(row -> new BigDecimal(row.get("priceMin"))).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = group.getValue().stream().map(row -> new BigDecimal(row.get("priceMin"))).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            if (product.getPriceMinEur() == null || product.getPriceMaxEur() == null
                    || product.getPriceMinEur().compareTo(min) != 0 || product.getPriceMaxEur().compareTo(max) != 0) {
                parentHistory.add(new CatalogPriceHistory(group.getKey(), min, max, capturedAt));
            }
            product.setSupplier(SUPPLIER);
            product.setExternalId(source.get("externalId"));
            product.setSupplierName(source.get("name"));
            product.setCategory(source.get("category"));
            product.setPackageOptions(group.getValue().stream().map(row -> row.get("packageWeight")).filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(", ")));
            product.setPriceMinEur(min);
            product.setPriceMaxEur(max);
            product.setProductUrl(source.get("productUrl"));
            product.setImageUrl(source.get("imageUrl"));
            product.setMatchMethod(matchMethod);
            product.setCapturedAt(capturedAt);
            String catalogCode = product.getCatalogProduct().getInternalCode();
            group.getValue().forEach(row -> row.put("catalogCode", catalogCode));
            supplierProducts.add(product);
        }
        supplierProductsRepository.saveAll(supplierProducts);

        Map<String, SupplierProduct> productsByCode = supplierProducts.stream()
                .collect(Collectors.toMap(SupplierProduct::getSupplierCode, Function.identity()));
        List<String> offerCodes = rows.stream().map(row -> row.get("supplierCode")).toList();
        Map<String, SupplierOffer> existingOffers = offersRepository.findAllById(offerCodes).stream()
                .collect(Collectors.toMap(SupplierOffer::getOfferCode, Function.identity()));
        List<SupplierOffer> offers = new ArrayList<>();
        List<SupplierOfferPriceHistory> offerHistory = new ArrayList<>();
        for (Map<String, String> source : rows) {
            String offerCode = source.get("supplierCode");
            BigDecimal price = new BigDecimal(source.get("priceMin"));
            SupplierOffer offer = existingOffers.getOrDefault(offerCode, new SupplierOffer(offerCode));
            if (offer.getPriceEur() == null || offer.getPriceEur().compareTo(price) != 0) {
                offerHistory.add(new SupplierOfferPriceHistory(offerCode, price, capturedAt));
            }
            int grams = Integer.parseInt(source.get("packageGrams"));
            int milliliters = Integer.parseInt(source.getOrDefault("packageMilliliters", "0"));
            offer.setSupplierProduct(productsByCode.get(source.get("supplierProductCode")));
            offer.setVariantExternalId(source.get("variantExternalId"));
            offer.setPackageLabel(source.get("packageWeight"));
            offer.setTotalQuantityLabel(source.get("totalQuantity"));
            offer.setPackageGrams(grams == 0 ? null : grams);
            offer.setPackageMilliliters(milliliters == 0 ? null : milliliters);
            offer.setMeasureUnit(source.get("measureUnit"));
            offer.setUnitsPerPackage(1);
            offer.setPriceEur(price);
            offer.setPricePerUnitEur(price);
            offer.setPricePerKgEur(grams == 0 ? null : price.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(grams), 2, RoundingMode.HALF_UP));
            offer.setPricePerLiterEur(milliliters == 0 ? null : price.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(milliliters), 2, RoundingMode.HALF_UP));
            offer.setActive(true);
            offer.setCapturedAt(capturedAt);
            offers.add(offer);
        }

        offersRepository.saveAll(offers);
        historyRepository.saveAll(parentHistory);
        offerHistoryRepository.saveAll(offerHistory);
    }

    private String normalizeName(String name) {
        return Normalizer.normalize(Objects.toString(name, ""), Normalizer.Form.NFKC)
                .toLowerCase(new Locale("bg"))
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private Map<String, String> toMap(SupplierOffer offer) {
        SupplierProduct product = offer.getSupplierProduct();
        Map<String, String> result = new HashMap<>();
        result.put("catalogCode", product.getCatalogProduct().getInternalCode());
        result.put("supplierProductCode", product.getSupplierCode());
        result.put("supplierCode", offer.getOfferCode());
        result.put("externalId", product.getExternalId());
        result.put("variantExternalId", offer.getVariantExternalId());
        result.put("name", product.getSupplierName());
        result.put("category", Objects.toString(product.getCategory(), ""));
        result.put("packageWeight", Objects.toString(offer.getPackageLabel(), ""));
        result.put("totalQuantity", Objects.toString(offer.getTotalQuantityLabel(), offer.getPackageLabel()));
        result.put("packageGrams", Objects.toString(offer.getPackageGrams(), "0"));
        result.put("packageMilliliters", Objects.toString(offer.getPackageMilliliters(), "0"));
        result.put("packageBaseQuantity", Objects.toString(offer.getPackageGrams() != null ? offer.getPackageGrams() : offer.getPackageMilliliters(), "0"));
        result.put("measureUnit", Objects.toString(offer.getMeasureUnit(), ""));
        result.put("unitsPerPackage", offer.getUnitsPerPackage() + " бр.");
        result.put("priceMin", offer.getPriceEur().toPlainString());
        result.put("price", "€" + offer.getPriceEur());
        result.put("pricePerUnit", offer.getPricePerUnitEur() == null ? "" : "€" + offer.getPricePerUnitEur() + "/бр.");
        result.put("pricePerKg", offer.getPricePerKgEur() == null ? "" : "€" + offer.getPricePerKgEur() + "/кг");
        result.put("pricePerLiter", offer.getPricePerLiterEur() == null ? "" : "€" + offer.getPricePerLiterEur() + "/л");
        result.put("pricePerMeasure", offer.getPricePerLiterEur() == null
                ? result.get("pricePerKg")
                : result.get("pricePerLiter"));
        result.put("productUrl", Objects.toString(product.getProductUrl(), ""));
        result.put("imageUrl", Objects.toString(product.getImageUrl(), ""));
        result.put("addedAt", DISPLAY_TIME.format(offer.getCapturedAt()));
        return result;
    }
}
