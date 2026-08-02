package com.example.demo.service;

import com.example.demo.catalog.CatalogProduct;
import com.example.demo.catalog.CatalogProductRepository;
import com.example.demo.catalog.SupplierOffer;
import com.example.demo.catalog.SupplierOfferPriceHistory;
import com.example.demo.catalog.SupplierOfferPriceHistoryRepository;
import com.example.demo.catalog.SupplierOfferRepository;
import com.example.demo.catalog.SupplierProduct;
import com.example.demo.catalog.SupplierProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MetroCatalogService {

    private static final String SUPPLIER = "metro";
    private static final Pattern METRO_ID = Pattern.compile("BTY-(X\\d+)/(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private final CatalogProductRepository catalogProductsRepository;
    private final SupplierProductRepository supplierProductsRepository;
    private final SupplierOfferRepository offersRepository;
    private final SupplierOfferPriceHistoryRepository offerHistoryRepository;
    private volatile int lastSignature;
    private volatile List<Map<String, String>> cached = List.of();

    public MetroCatalogService(CatalogProductRepository catalogProductsRepository,
                               SupplierProductRepository supplierProductsRepository,
                               SupplierOfferRepository offersRepository,
                               SupplierOfferPriceHistoryRepository offerHistoryRepository) {
        this.catalogProductsRepository = catalogProductsRepository;
        this.supplierProductsRepository = supplierProductsRepository;
        this.offersRepository = offersRepository;
        this.offerHistoryRepository = offerHistoryRepository;
    }

    public synchronized List<Map<String, String>> attachCatalogCodes(List<Map<String, String>> sourceProducts) {
        if (sourceProducts == null || sourceProducts.isEmpty()) return List.of();
        int signature = sourceProducts.stream()
                .map(product -> product.getOrDefault("productUrl", "") + '|' + product.getOrDefault("price", ""))
                .toList().hashCode();
        if (signature == lastSignature && !cached.isEmpty()) return cached;

        List<String> codes = sourceProducts.stream().map(this::supplierCode).distinct().toList();
        Map<String, SupplierProduct> existingProducts = supplierProductsRepository.findAllById(codes).stream()
                .collect(Collectors.toMap(SupplierProduct::getSupplierCode, Function.identity()));
        Map<String, SupplierOffer> existingOffers = offersRepository.findAllById(codes).stream()
                .collect(Collectors.toMap(SupplierOffer::getOfferCode, Function.identity()));
        Instant capturedAt = Instant.now();
        List<SupplierProduct> supplierProducts = new ArrayList<>();
        List<SupplierOffer> offers = new ArrayList<>();
        List<SupplierOfferPriceHistory> priceHistory = new ArrayList<>();
        List<Map<String, String>> decorated = new ArrayList<>();

        for (Map<String, String> source : sourceProducts) {
            String code = supplierCode(source);
            SupplierProduct supplierProduct = existingProducts.get(code);
            String matchMethod = "EXISTING_LINK";
            if (supplierProduct == null) {
                supplierProduct = new SupplierProduct(code);
                String normalizedName = normalizeName(source.get("name"));
                CatalogProduct master = catalogProductsRepository.findFirstByNormalizedName(normalizedName).orElse(null);
                if (master == null) {
                    master = new CatalogProduct();
                    master.setName(source.getOrDefault("name", "METRO продукт"));
                    master.setNormalizedName(normalizedName);
                    master.setCategory("Подправки");
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
                supplierProduct.setCatalogProduct(master);
            }

            BigDecimal price = decimal(source.get("price"));
            int grams = packageGrams(source.get("packageWeight"));
            supplierProduct.setSupplier(SUPPLIER);
            supplierProduct.setExternalId(code.substring(3));
            supplierProduct.setSupplierName(source.getOrDefault("name", ""));
            supplierProduct.setCategory("Подправки");
            supplierProduct.setPackageOptions(source.getOrDefault("packageWeight", ""));
            supplierProduct.setPriceMinEur(price);
            supplierProduct.setPriceMaxEur(price);
            supplierProduct.setProductUrl(source.getOrDefault("productUrl", ""));
            supplierProduct.setImageUrl(source.getOrDefault("imageUrl", ""));
            supplierProduct.setMatchMethod(matchMethod);
            supplierProduct.setCapturedAt(capturedAt);
            supplierProducts.add(supplierProduct);

            SupplierOffer offer = existingOffers.getOrDefault(code, new SupplierOffer(code));
            if (offer.getPriceEur() == null || offer.getPriceEur().compareTo(price) != 0) {
                priceHistory.add(new SupplierOfferPriceHistory(code, price, capturedAt));
            }
            offer.setSupplierProduct(supplierProduct);
            offer.setVariantExternalId(code.substring(3));
            offer.setPackageLabel(source.getOrDefault("packageWeight", ""));
            offer.setPackageGrams(grams == 0 ? null : grams);
            offer.setUnitsPerPackage(integer(source.get("unitsPerPackage"), 1));
            offer.setPriceEur(price);
            offer.setPricePerKgEur(grams == 0 ? null : price.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(grams), 2, RoundingMode.HALF_UP));
            offer.setActive(true);
            offer.setCapturedAt(capturedAt);
            offers.add(offer);

            Map<String, String> copy = new LinkedHashMap<>(source);
            copy.put("catalogCode", supplierProduct.getCatalogProduct().getInternalCode());
            copy.put("supplierCode", code);
            copy.put("packageGrams", Integer.toString(grams));
            decorated.add(copy);
        }
        supplierProductsRepository.saveAll(supplierProducts);
        Map<String, SupplierOffer> staleOffers = offersRepository.findAllBySupplier(SUPPLIER).stream()
                .filter(offer -> !codes.contains(offer.getOfferCode()))
                .collect(Collectors.toMap(SupplierOffer::getOfferCode, Function.identity()));
        staleOffers.values().forEach(offer -> offer.setActive(false));
        offers.addAll(staleOffers.values());
        offersRepository.saveAll(offers);
        offerHistoryRepository.saveAll(priceHistory);
        cached = List.copyOf(decorated);
        lastSignature = signature;
        return cached;
    }

    private String supplierCode(Map<String, String> product) {
        String url = product.getOrDefault("productUrl", "");
        Matcher matcher = METRO_ID.matcher(url);
        String external = matcher.find() ? (matcher.group(1) + '-' + matcher.group(2)).toUpperCase(Locale.ROOT)
                : Integer.toUnsignedString(url.hashCode(), 36).toUpperCase(Locale.ROOT);
        return "MT-" + external;
    }

    private String normalizeName(String name) {
        return Normalizer.normalize(Objects.toString(name, ""), Normalizer.Form.NFKC)
                .toLowerCase(new Locale("bg"))
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private BigDecimal decimal(String value) {
        Matcher matcher = NUMBER.matcher(Objects.toString(value, ""));
        return matcher.find() ? new BigDecimal(matcher.group(1).replace(',', '.')).setScale(2) : BigDecimal.ZERO.setScale(2);
    }

    private int integer(String value, int fallback) {
        Matcher matcher = NUMBER.matcher(Objects.toString(value, ""));
        return matcher.find() ? new BigDecimal(matcher.group(1).replace(',', '.')).intValue() : fallback;
    }

    private int packageGrams(String value) {
        BigDecimal number = decimal(value);
        String normalized = Objects.toString(value, "").toLowerCase(new Locale("bg"));
        if (normalized.contains("кг") || normalized.contains("kg")) number = number.multiply(BigDecimal.valueOf(1000));
        return number.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
