package com.example.demo.controller;

import com.example.demo.service.KauflandScraperService;
import com.example.demo.service.MetroScraperService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
            MetroScraperService metroScraper = new MetroScraperService();
            List<Map<String, String>> metroProducts = metroScraper.scrapeSpicePrices();
            model.addAttribute("metroProducts", metroProducts);
        } catch (Exception e) {
            model.addAttribute("metroError", e.getMessage());
        }

        // Kaufland.bg offers (spices, dried fruits, nuts, mushrooms)
        try {
            KauflandScraperService kauflandScraper = new KauflandScraperService();
            List<Map<String, String>> kauflandProducts = kauflandScraper.scrapeProducts();
            model.addAttribute("kauflandProducts", kauflandProducts);
        } catch (Exception e) {
            model.addAttribute("kauflandError", e.getMessage());
        }

        return "market";
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
