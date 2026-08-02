package com.example.demo.controller;

import com.example.demo.service.MetroScraperService;
import com.example.demo.service.GourmetSpiceService;
import com.example.demo.service.MetroCatalogService;
import com.example.demo.service.CatalogService;
import com.example.demo.catalog.CatalogProduct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Controller
public class HomeController {

    private final GourmetSpiceService gourmetSpiceService;
    private final MetroCatalogService metroCatalogService;
    private final CatalogService catalogService;

    public HomeController(GourmetSpiceService gourmetSpiceService, MetroCatalogService metroCatalogService,
                          CatalogService catalogService) {
        this.gourmetSpiceService = gourmetSpiceService;
        this.metroCatalogService = metroCatalogService;
        this.catalogService = catalogService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("title", "Spring Boot + Thymeleaf");
        model.addAttribute("message", "Welcome to Spring Boot with Thymeleaf!");
        model.addAttribute("currentTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        List<String> features = Arrays.asList(
                "Spring Boot 3.5.3",
                "Thymeleaf Template Engine",
                "Hot Reload with DevTools",
                "Java 17",
                "Maven Build"
        );
        model.addAttribute("features", features);

        return "index";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("title", "About");
        model.addAttribute("message", "This is a demo application built with Spring Boot and Thymeleaf.");
        return "about";
    }

    @GetMapping("/market")
    public String market(Model model) {
        model.addAttribute("title", "Market");

        // Metro.bg spice prices
        try {
            List<Map<String, String>> metroProducts = metroCatalogService.attachCatalogCodes(MetroScraperService.getCachedProductsAndRefresh());
            model.addAttribute("metroProducts", metroProducts);
        } catch (Exception e) {
            model.addAttribute("metroError", e.getMessage());
        }

        // Never block the initial page render with another external website.
        model.addAttribute("kauflandProducts", List.of());

        return "market";
    }

    @GetMapping("/api/metro-prices")
    @ResponseBody
    public Map<String, Object> metroPrices() {
        List<Map<String, String>> products = metroCatalogService.attachCatalogCodes(MetroScraperService.getCachedProductsAndRefresh());
        return Map.of(
                "products", products,
                "refreshing", MetroScraperService.isRefreshing(),
                "error", MetroScraperService.getLastError());
    }

    @GetMapping("/api/gourmet-prices")
    @ResponseBody
    public Map<String, Object> gourmetPrices() {
        List<Map<String, String>> products = gourmetSpiceService.getCachedProductsAndRefresh();
        return Map.of(
                "products", products,
                "refreshing", gourmetSpiceService.isRefreshing(),
                "error", gourmetSpiceService.getLastError());
    }

    @GetMapping("/api/metro-images/{fileName:.+}")
    @ResponseBody
    public ResponseEntity<Resource> metroImage(@PathVariable String fileName) {
        if (!fileName.matches("[a-f0-9]{64}\\.(jpg|png|webp|gif)")) {
            return ResponseEntity.notFound().build();
        }
        Path image = MetroScraperService.IMAGE_CACHE_DIR.resolve(fileName).normalize();
        if (!image.startsWith(MetroScraperService.IMAGE_CACHE_DIR.normalize()) || !Files.isRegularFile(image)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String detectedType = Files.probeContentType(image);
            MediaType mediaType = detectedType == null
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(detectedType);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .contentType(mediaType)
                    .body(new FileSystemResource(image));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/sales")
    public String sales(Model model) {
        model.addAttribute("title", "Sales");
        return "sales";
    }

    @GetMapping("/stock")
    public String stock(Model model) {
        model.addAttribute("title", "Stock");
        return "stock";
    }

    @GetMapping("/shop")
    public String shop(Model model) {
        model.addAttribute("title", "Shop");
        return "shop";
    }

    @GetMapping("/catalog")
    public String catalog(Model model) {
        List<CatalogProduct> products = catalogService.getAllProducts();
        model.addAttribute("title", "HOREMAG каталог");
        model.addAttribute("products", products);
        model.addAttribute("categories", products.stream()
                .map(CatalogProduct::getCategory)
                .filter(category -> category != null && !category.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());
        model.addAttribute("productCount", products.size());
        model.addAttribute("manualCount", products.stream().filter(product -> "MANUAL".equals(product.getSource())).count());
        model.addAttribute("packagingCount", products.stream().filter(product -> "PACKAGING".equals(product.getProductType())).count());
        return "catalog";
    }

    @PostMapping("/catalog/products")
    public String addCatalogProduct(@RequestParam String name,
                                    @RequestParam(defaultValue = "INGREDIENT") String productType,
                                    @RequestParam String category,
                                    @RequestParam(defaultValue = "бр") String baseUnit,
                                    @RequestParam(defaultValue = "") String packageOptions,
                                    @RequestParam(defaultValue = "") String notes,
                                    RedirectAttributes redirectAttributes) {
        try {
            CatalogProduct product = catalogService.createProduct(
                    name, productType, category, baseUnit, packageOptions, notes);
            redirectAttributes.addFlashAttribute("catalogSuccess",
                    "Добавен е " + product.getInternalCode() + " — " + product.getName());
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("catalogError", e.getMessage());
        }
        return "redirect:/catalog";
    }

}
