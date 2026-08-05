package com.example.demo.controller;

import com.example.demo.service.MetroScraperService;
import com.example.demo.service.GourmetSpiceService;
import com.example.demo.service.MetroCatalogService;
import com.example.demo.service.CatalogService;
import com.example.demo.catalog.CatalogProduct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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
            List<Map<String, String>> metroProducts = visibleMetroProducts();
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
        List<Map<String, String>> products = visibleMetroProducts();
        return Map.of(
                "products", products,
                "refreshing", MetroScraperService.isRefreshing(),
                "error", MetroScraperService.getLastError());
    }

    private List<Map<String, String>> visibleMetroProducts() {
        return metroCatalogService.attachCatalogCodes(MetroScraperService.getCachedProductsAndRefresh()).stream()
                .filter(product -> !product.getOrDefault("name", "").toLowerCase(Locale.ROOT).contains("сос"))
                .toList();
    }

    /**
     * Receives the table exactly as it is currently displayed in the browser.
     * Filtering, sorting and chosen columns are therefore preserved in the workbook.
     */
    @PostMapping(value = "/api/metro-prices/export", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> exportMetroPrices(@RequestBody MetroExportRequest export) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Metro prices");
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int column = 0; column < export.headers().size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(export.headers().get(column));
                cell.setCellStyle(headerStyle);
            }
            for (int rowIndex = 0; rowIndex < export.rows().size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = export.rows().get(rowIndex);
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            sheet.createFreezePane(0, 1);
            for (int column = 0; column < export.headers().size(); column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 20000));
            }
            workbook.write(output);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header("Content-Disposition", ContentDisposition.attachment()
                            .filename("metro-prices.xlsx")
                            .build()
                            .toString())
                    .body(output.toByteArray());
        }
    }

    public record MetroExportRequest(List<String> headers, List<List<String>> rows) {
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
