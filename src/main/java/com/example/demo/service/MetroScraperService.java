package com.example.demo.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Metro.bg spice price scraper using Selenium WebDriver for JS-rendered pages.
 */
public class MetroScraperService {

    private static final String METRO_SPICE_URL =
            "https://shop.metro.bg/shop/category/" +
            "%D1%85%D1%80%D0%B0%D0%BD%D0%B8%D1%82%D0%B5%D0%BB%D0%BD%D0%B8-%D1%81%D1%82%D0%BE%D0%BA%D0%B8/" +
            "%D0%BF%D0%B0%D0%BA%D0%B5%D1%82%D0%B8%D1%80%D0%B0%D0%BD%D0%B8-%D1%85%D1%80%D0%B0%D0%BD%D0%B8/" +
            "%D0%BE%D0%B2%D0%BA%D1%83%D1%81%D0%B8%D1%82%D0%B5%D0%BB%D0%B8-%D0%BF%D0%BE%D0%B4%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8-%D0%B8-%D1%81%D0%BE%D1%81%D0%BE%D0%B2%D0%B5/" +
            "%D0%BF%D0%BE%D0%B4%D0%BF%D1%80%D0%B0%D0%B2%D0%BA%D0%B8";

    private static final int MAX_PRODUCTS = 40;
    private static final Duration PAGE_LOAD_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration ELEMENT_TIMEOUT = Duration.ofSeconds(15);

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

            // Metro uses custom elements - wait for product tiles/links
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("a[href*='/product/'], [class*=product], [data-testid=product-card]")));
            } catch (TimeoutException e) {
                // If product links don't appear, try waiting a bit longer and check body text
                Thread.sleep(5000);
            }

            // Additional wait for prices to render
            Thread.sleep(3000);

            // Temporary diagnostics for the live page structure.
            Files.writeString(Path.of("metro-rendered.html"),
                    (String) ((JavascriptExecutor) driver).executeScript("return document.documentElement.outerHTML"));

            // Try multiple selector strategies for product cards
            List<WebElement> productCards = driver.findElements(
                    By.cssSelector("a[href*='/product/']"));

            // If too many raw links, try to narrow down to actual product tiles
            if (productCards.size() > 50) {
                productCards = driver.findElements(
                        By.cssSelector("[class*=product-tile], [class*=product-card], [class*=tile], article, li[class*=product]"));
            }

            for (WebElement card : productCards) {
                try {
                    Map<String, String> product = new HashMap<>();

                    // Name
                    String name = extractText(card,
                            "h2, h3, [class*=name], [class*=title], [class*=description], span:not([class*=price])");
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
                    String packageSize = extractText(card,
                            "[class*=gram], [class*=weight], [class*=size], [class*=quantity]");
                    product.put("packageSize", cleanText(packageSize));

                    // Image
                    WebElement img = safeFindElement(card, "img");
                    product.put("imageUrl", img != null ? img.getAttribute("src") : "");

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

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
