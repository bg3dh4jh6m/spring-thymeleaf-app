package com.example.demo.controller;

import com.example.demo.service.MetroScraperService;
import com.example.demo.service.GourmetSpiceService;
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

    public HomeController(GourmetSpiceService gourmetSpiceService) {
        this.gourmetSpiceService = gourmetSpiceService;
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
            List<Map<String, String>> metroProducts = MetroScraperService.getCachedProductsAndRefresh();
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
        List<Map<String, String>> products = MetroScraperService.getCachedProductsAndRefresh();
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

}
