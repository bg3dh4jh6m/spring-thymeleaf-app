package com.example.demo.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kaufland.bg offer page scraper for spices, dried fruits, nuts, mushrooms.
 */
public class KauflandScraperService {

    private static final String KAUFLAND_OFFERS_URL =
            "https://www.kaufland.bg/aktualni-predlozheniya/oferti.html";

    private static final String[] CATEGORY_KEYWORDS = {
            "подправк", "бахар", "чубриц", "мента", "риган", "розмарин",
            "магданоз", "копър", "кимион", "куркума", "джинджифил",
            "канел", "карамфил", "индийско", "червен пипер", "чер пипер",
            "босилек", "мащерка", "девесил", "самодивск", "шарена сол",
            "универсална подправка", "пипер", "сол", "вегета",
            "чесън на прах", "лук на прах", "горчица", "дафинов",
            "сушен", "ядк", "орех", "бадем", "кашу", "фъстък", "шам-фъст",
            "лешник", "тиквен", "слънчоглед", "семк", "стафид", "синя слив",
            "кайси", "фурм", "боровинк", "червена боровинк", "гоџи",
            "сушени плод", "чиа", "ленен",
            "гъб", "манатарк", "пачи крак", "кладиниц", "печурк",
            "шиитак", "майтаке", "рейши", "кордицепс",
    };

    public List<Map<String, String>> scrapeProducts() {
        try {
            Document doc = Jsoup.connect(KAUFLAND_OFFERS_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept-Language", "bg-BG,bg;q=0.9,en;q=0.8")
                    .timeout(20000)
                    .maxBodySize(10_000_000)
                    .get();

            return parseKauflandProducts(doc);

        } catch (Exception e) {
            return createErrorList("Грешка при зареждане на Kaufland: " + e.getMessage());
        }
    }

    private List<Map<String, String>> parseKauflandProducts(Document doc) {
        List<Map<String, String>> products = new ArrayList<>();

        Elements productTiles = doc.select(".k-product-tile");

        for (Element tile : productTiles) {
            Map<String, String> product = new HashMap<>();

            Element titleEl = tile.selectFirst(".k-product-tile__title");
            String name = titleEl != null ? titleEl.text().trim() : "";
            product.put("name", name);

            Element subtitleEl = tile.selectFirst(".k-product-tile__subtitle");
            product.put("description", subtitleEl != null ? subtitleEl.text().trim() : "");

            Element priceEl = tile.selectFirst(".k-price-tag__price");
            product.put("price", priceEl != null ? priceEl.text().trim() : "");

            Element oldPriceEl = tile.selectFirst(".k-price-tag__old-price");
            product.put("oldPrice", oldPriceEl != null ? oldPriceEl.text().trim() : "");

            Element unitPriceEl = tile.selectFirst(".k-product-tile__unit-price");
            product.put("unitPrice", unitPriceEl != null ? unitPriceEl.text().trim() : "");

            Element basePriceEl = tile.selectFirst(".k-product-tile__base-price");
            product.put("basePrice", basePriceEl != null ? basePriceEl.text().trim() : "");

            Element imgEl = tile.selectFirst("img");
            product.put("imageUrl", imgEl != null ? imgEl.absUrl("src") : "");

            // Filter by category keywords
            String lower = name.toLowerCase();
            boolean matches = false;
            for (String kw : CATEGORY_KEYWORDS) {
                if (lower.contains(kw)) {
                    matches = true;
                    break;
                }
            }

            if (matches && !name.isBlank()) {
                products.add(product);
            }

            if (products.size() >= 50) break;
        }

        // If filtered list empty, return unfiltered
        if (products.isEmpty()) {
            products.clear();
            for (Element tile : productTiles) {
                Map<String, String> product = new HashMap<>();
                Element titleEl = tile.selectFirst(".k-product-tile__title");
                product.put("name", titleEl != null ? titleEl.text().trim() : "");
                Element subtitleEl = tile.selectFirst(".k-product-tile__subtitle");
                product.put("description", subtitleEl != null ? subtitleEl.text().trim() : "");
                Element priceEl = tile.selectFirst(".k-price-tag__price");
                product.put("price", priceEl != null ? priceEl.text().trim() : "");
                Element oldPriceEl = tile.selectFirst(".k-price-tag__old-price");
                product.put("oldPrice", oldPriceEl != null ? oldPriceEl.text().trim() : "");
                Element unitPriceEl = tile.selectFirst(".k-product-tile__unit-price");
                product.put("unitPrice", unitPriceEl != null ? unitPriceEl.text().trim() : "");
                Element basePriceEl = tile.selectFirst(".k-product-tile__base-price");
                product.put("basePrice", basePriceEl != null ? basePriceEl.text().trim() : "");
                Element imgEl = tile.selectFirst("img");
                product.put("imageUrl", imgEl != null ? imgEl.absUrl("src") : "");
                if (!product.get("name").isBlank()) products.add(product);
                if (products.size() >= 40) break;
            }
        }

        if (products.isEmpty()) {
            return createMessageList("Няма намерени продукти от Kaufland.");
        }

        return products;
    }

    private List<Map<String, String>> createErrorList(String message) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, String> error = new HashMap<>();
        error.put("name", "Грешка");
        error.put("description", message);
        error.put("price", "");
        error.put("oldPrice", "");
        error.put("unitPrice", "");
        error.put("basePrice", "");
        error.put("imageUrl", "");
        result.add(error);
        return result;
    }

    private List<Map<String, String>> createMessageList(String message) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, String> msg = new HashMap<>();
        msg.put("name", message);
        msg.put("description", "");
        msg.put("price", "");
        msg.put("oldPrice", "");
        msg.put("unitPrice", "");
        msg.put("basePrice", "");
        msg.put("imageUrl", "");
        result.add(msg);
        return result;
    }
}