package com.example.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metro.bg spice price scraper using Selenium WebDriver for JS-rendered pages.
 */
public class MetroScraperService {

    private static final Path CACHE_FILE = Path.of("data", "metro-products.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile List<Map<String, String>> cachedProducts = loadCache();
    private static volatile long lastRefreshStartedAt = 0;
    private static volatile String lastError = "";
    private static final long REFRESH_INTERVAL_MS = Duration.ofMinutes(15).toMillis();
    private static final long EMPTY_RETRY_INTERVAL_MS = Duration.ofMinutes(1).toMillis();

    /** Returns cached data immediately and refreshes it asynchronously when needed. */
    public static List<Map<String, String>> getCachedProductsAndRefresh() {
        long now = System.currentTimeMillis();
        long retryInterval = cachedProducts.isEmpty() ? EMPTY_RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS;
        if (now - lastRefreshStartedAt >= retryInterval && REFRESHING.compareAndSet(false, true)) {
            lastRefreshStartedAt = now;
            CompletableFuture.runAsync(() -> {
                try {
                    List<Map<String, String>> fresh = new MetroScraperService().scrapeSpicePrices();
                    if (!fresh.isEmpty()) publishSnapshot(fresh);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    REFRESHING.set(false);
                }
            });
        }
        return cachedProducts;
    }

    public static boolean isRefreshing() {
        return REFRESHING.get();
    }

    public static String getLastError() {
        return lastError;
    }

    private static void publishSnapshot(List<Map<String, String>> products) {
        List<Map<String, String>> validProducts = products.stream()
                .filter(p -> p.getOrDefault("name", "").length() > 3)
                .filter(p -> p.getOrDefault("price", "").contains("€"))
                .toList();
        if (validProducts.isEmpty()) return;
        cachedProducts = List.copyOf(validProducts);
        saveCache(validProducts);
        lastError = "";
    }

    private static List<Map<String, String>> loadCache() {
        try {
            if (Files.exists(CACHE_FILE)) {
                return JSON.readValue(CACHE_FILE.toFile(), new TypeReference<List<Map<String, String>>>() {});
            }
        } catch (Exception ignored) { }
        return Collections.emptyList();
    }

    private static void saveCache(List<Map<String, String>> products) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(CACHE_FILE.toFile(), products);
        } catch (Exception ignored) { }
    }

    private static final String METRO_SPICE_URL =
            "https://shop.metro.bg/shop/category/" +
            "%D1%85%D1%80%D0%B0%D0%BD%D0%B8%D1%82%D0%B5%D0%BB%D0%BD%D0%B8-%D1%81%D1%82%D0%BE%D0%BA%D0%B8/" +
            "%D0%BF%D0%B0%D0%BA%D0%B5%D1%82%D0%B8%D1%80%D0%B0%D0%BD%D0%B8-%D1%85%D1%80%D0%B0%D0%BD%D0%B8/" +
            "%D0%BE%D0%B2%D0%BA%D1%83%D1%81%D0%B8%D1%82%D0%B5%D0%BB%D0%B8-%D0%BF%D0%BE%D0%B4%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8-%D0%B8-%D1%81%D0%BE%D1%81%D0%BE%D0%B2%D0%B5/" +
            "%D0%BF%D0%BE%D0%B4%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8";

    private static final int MAX_PRODUCTS = 250;
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration ELEMENT_TIMEOUT = Duration.ofSeconds(45);

    public List<Map<String, String>> scrapeSpicePrices() {
        List<Map<String, String>> products = new ArrayList<>();
        WebDriver driver = null;

        try {
            // Auto-setup ChromeDriver
            WebDriverManager.chromedriver().setup();

            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--lang=bg-BG");
            options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");

            driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(PAGE_LOAD_TIMEOUT);

            driver.get(METRO_SPICE_URL);

            // Wait for product cards to appear (JS-rendered content)
            WebDriverWait wait = new WebDriverWait(driver, ELEMENT_TIMEOUT);

            // Publish the first visible cards immediately, then grow the same
            // snapshot after every "show more" response.
            if (driver != null) {
                return scrapeProgressively(driver, wait);
            }

            wait.until(d -> d.findElements(By.cssSelector(".sd-articlecard")).size() >= 20);
            loadAllProducts(driver, wait);

            // Try multiple selector strategies for product cards
            List<WebElement> productCards = driver.findElements(By.cssSelector(".sd-articlecard"));

            for (WebElement card : productCards) {
                try {
                    Map<String, String> product = new HashMap<>();

                    // Name
                    String name = extractText(card, "a.title h4");
                    product.put("name", cleanText(name));

                    // Price
                    String price = extractText(card,
                            "[class*=price], [class*=current-price], [class*=selling-price], span:contains('лв'), span:contains('lv')");
                    product.put("price", cleanText(price));

                    // Price per kg
                    String pricePerKg = extractText(card,
                            "[class*=unit-price], [class*=base-price], [class*=price-per-kg], span:contains('/кг'), span:contains('/kg')");
                    product.put("pricePerKg", cleanText(pricePerKg));

                    // Package size
                    String packageSize = extractText(card, ".bundle.packaging-type");
                    product.put("packageSize", cleanText(packageSize));

                    // Image
                    WebElement img = safeFindElement(card, "img");
                    product.put("imageUrl", img != null ? img.getAttribute("src") : "");

                    String bgnPrice = extractBgnPrice(card);
                    product.put("price", bgnPrice);
                    product.put("packageSize", extractWeight(name));
                    product.put("pricePerKg", calculatePricePerKg(bgnPrice, name));
                    product.put("manufacturer", "");
                    product.put("trademark", "");

                    if (!product.get("name").isBlank() && !product.get("name").equals(cleanText(price))) {
                        // Avoid duplicates by name
                        boolean duplicate = products.stream()
                                .anyMatch(p -> p.get("name").equals(product.get("name")));
                        if (!duplicate && product.get("name").length() > 3) {
                            products.add(product);
                        }
                    }

                    if (products.size() >= MAX_PRODUCTS) break;

                } catch (Exception ignored) {
                    // Skip problematic cards
                }
            }

            // Fallback: if still empty, try scraping product rows from the whole page
            if (products.isEmpty()) {
                fallbackScrape(driver, products);
            }

            if (products.isEmpty()) {
                Map<String, String> msg = new HashMap<>();
                msg.put("name", "Неуспешно извличане на данни от Metro.bg");
                msg.put("price", "Сайтът блокира автоматичния достъп. Опитайте отново по-късно.");
                msg.put("pricePerKg", "");
                msg.put("packageSize", "");
                msg.put("manufacturer", "");
                msg.put("trademark", "");
                msg.put("imageUrl", "");
                products.add(msg);
            }

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("name", "Грешка при свързване с Metro.bg");
            error.put("price", e.getClass().getSimpleName() + ": " + e.getMessage());
            error.put("pricePerKg", "");
            error.put("packageSize", "");
            error.put("manufacturer", "");
            error.put("trademark", "");
            error.put("imageUrl", "");
            products.add(error);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignored) {}
            }
        }

        return products;
    }

    private List<Map<String, String>> scrapeProgressively(WebDriver driver, WebDriverWait wait) {
        LinkedHashMap<String, Map<String, String>> collected = new LinkedHashMap<>();

        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".sd-articlecard")));
        collectRenderedCards(driver, collected);
        publishSnapshot(new ArrayList<>(collected.values()));

        // The first cards can be recommendations. Wait briefly for the category
        // grid and its load-more control, publishing anything new meanwhile.
        try {
            wait.until(d -> d.findElements(By.cssSelector(".sd-articlecard")).size() > collected.size()
                    || !d.findElements(By.cssSelector("a.mfcss_load-more-articles")).isEmpty());
            collectRenderedCards(driver, collected);
            publishSnapshot(new ArrayList<>(collected.values()));
        } catch (Exception ignored) { }

        for (int page = 0; page < 12 && collected.size() < MAX_PRODUCTS; page++) {
            List<WebElement> buttons = driver.findElements(By.cssSelector("a.mfcss_load-more-articles"));
            if (buttons.isEmpty() || !buttons.get(0).isDisplayed()) break;

            int previousCardCount = driver.findElements(By.cssSelector(".sd-articlecard")).size();
            try {
                WebElement button = buttons.get(0);
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", button);
                wait.until(d -> d.findElements(By.cssSelector(".sd-articlecard")).size() > previousCardCount);
                collectRenderedCards(driver, collected);
                publishSnapshot(new ArrayList<>(collected.values()));
            } catch (Exception ignored) {
                break;
            }
        }

        return new ArrayList<>(collected.values());
    }

    private void collectRenderedCards(WebDriver driver,
                                      LinkedHashMap<String, Map<String, String>> collected) {
        Document document = Jsoup.parse(driver.getPageSource(), METRO_SPICE_URL);
        String addedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        for (Element card : document.select(".sd-articlecard")) {
            Element title = card.selectFirst("a.title h4");
            if (title == null || title.text().isBlank()) continue;

            String name = cleanText(title.text());
            String price = card.select(".price-display-main-row .primary").stream()
                    .map(Element::text)
                    .map(this::cleanText)
                    .filter(text -> text.contains("€"))
                    .findFirst()
                    .orElse("")
                    .replace("вкл.ДДС", "")
                    .trim();
            if (price.isBlank()) continue;

            Element link = card.selectFirst("a.title");
            Element image = card.selectFirst("img");
            String key = link == null ? name : link.attr("href");

            Map<String, String> previous = collected.get(key);
            Map<String, String> product = new LinkedHashMap<>();
            product.put("name", name);
            product.put("price", price);
            product.put("packageSize", extractWeight(name));
            product.put("pricePerKg", calculatePricePerKg(price, name));
            product.put("imageUrl", image == null ? "" : image.absUrl("src"));
            product.put("productUrl", link == null ? "" : link.absUrl("href"));
            product.put("trademark", extractTrademark(name));
            product.put("manufacturer", "");
            product.put("addedAt", previous == null
                    ? findCachedAddedAt(name, addedAt)
                    : previous.getOrDefault("addedAt", addedAt));
            collected.put(key, product);
        }
    }

    private String findCachedAddedAt(String name, String fallback) {
        return cachedProducts.stream()
                .filter(p -> name.equals(p.get("name")))
                .map(p -> p.getOrDefault("addedAt", fallback))
                .findFirst()
                .orElse(fallback);
    }

    private String extractTrademark(String name) {
        String[] words = name.split("\\s+");
        if (words.length == 0) return "";
        if (words.length > 1 && words[0].equalsIgnoreCase("METRO")) return words[0] + " " + words[1];
        if (words.length > 1 && words[0].equalsIgnoreCase("Fine")) return words[0] + " " + words[1];
        return words[0];
    }

    private void fallbackScrape(WebDriver driver, List<Map<String, String>> products) {
        // Try to find any structured product data on the page
        List<WebElement> allDivs = driver.findElements(By.cssSelector("div"));
        final String[] nameBuffer = {""};
        final String[] priceBuffer = {""};

        for (WebElement div : allDivs) {
            try {
                String text = div.getText().trim();
                if (text.isEmpty() || text.length() > 200) continue;

                // Detect price patterns: digits followed by лв or lv
                if (text.matches(".*\\d+[.,]\\d+\\s*(лв|lv|BGN).*") && text.length() < 50) {
                    priceBuffer[0] = text;
                } else if (text.length() > 5 && text.length() < 100 && !text.contains("лв")
                        && Character.isLetter(text.charAt(0))) {
                    nameBuffer[0] = text;
                }

                if (!nameBuffer[0].isEmpty() && !priceBuffer[0].isEmpty()) {
                    Map<String, String> product = new HashMap<>();
                    product.put("name", nameBuffer[0]);
                    product.put("price", priceBuffer[0]);
                    product.put("pricePerKg", "");
                    product.put("packageSize", "");
                    product.put("manufacturer", "");
                    product.put("trademark", "");
                    product.put("imageUrl", "");

                    boolean duplicate = products.stream()
                            .anyMatch(p -> p.get("name").equals(nameBuffer[0]));
                    if (!duplicate) {
                        products.add(product);
                    }
                    nameBuffer[0] = "";
                    priceBuffer[0] = "";

                    if (products.size() >= MAX_PRODUCTS) break;
                }
            } catch (Exception ignored) {}
        }
    }

    /** Loads every server-provided page by pressing METRO's "show more" control. */
    private void loadAllProducts(WebDriver driver, WebDriverWait wait) {
        while (true) {
            List<WebElement> buttons = driver.findElements(By.cssSelector("a.mfcss_load-more-articles"));
            if (buttons.isEmpty() || !buttons.get(0).isDisplayed()) {
                return;
            }

            int currentCount = driver.findElements(By.cssSelector(".sd-articlecard")).size();
            try {
                WebElement button = buttons.get(0);
                ((org.openqa.selenium.JavascriptExecutor) driver)
                        .executeScript("arguments[0].scrollIntoView({block: 'center'}); arguments[0].click();", button);
                wait.until(d -> d.findElements(By.cssSelector(".sd-articlecard")).size() > currentCount);
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private String extractText(WebElement parent, String cssSelector) {
        WebElement el = safeFindElement(parent, cssSelector);
        return el != null ? el.getText().trim() : "";
    }

    private WebElement safeFindElement(WebElement parent, String cssSelector) {
        try {
            return parent.findElement(By.cssSelector(cssSelector));
        } catch (Exception e) {
            return null;
        }
    }

    private String extractBgnPrice(WebElement card) {
        for (WebElement price : card.findElements(By.cssSelector(".price-display-main-row .primary"))) {
            String text = cleanText(price.getText());
            if (text.contains("€")) return text.replace("вкл.ДДС", "").trim();
        }
        return "";
    }

    private String extractWeight(String name) {
        Matcher matcher = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(кг|kg|г|гр|g)\\b").matcher(name);
        return matcher.find() ? matcher.group(1) + " " + matcher.group(2) : "";
    }

    private String calculatePricePerKg(String price, String name) {
        Matcher priceMatcher = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*€").matcher(price);
        Matcher weightMatcher = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(кг|kg|г|гр|g)\\b").matcher(name);
        if (!priceMatcher.find() || !weightMatcher.find()) return "";
        double amount = Double.parseDouble(priceMatcher.group(1).replace(',', '.'));
        double weight = Double.parseDouble(weightMatcher.group(1).replace(',', '.'));
        String unit = weightMatcher.group(2).toLowerCase(Locale.ROOT);
        if (unit.equals("г") || unit.equals("гр") || unit.equals("g")) weight /= 1000;
        if (weight <= 0) return "";
        return String.format(Locale.US, "%.2f €/кг", amount / weight).replace('.', ',');
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
