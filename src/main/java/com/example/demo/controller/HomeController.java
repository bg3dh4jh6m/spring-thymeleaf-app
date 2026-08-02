package com.example.demo.controller;

import com.example.demo.service.KauflandScraperService;
import com.example.demo.service.MetroScraperService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

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
