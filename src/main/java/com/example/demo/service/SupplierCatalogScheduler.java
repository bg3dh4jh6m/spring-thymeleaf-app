package com.example.demo.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SupplierCatalogScheduler {

    private final GourmetSpiceService gourmetSpiceService;
    private final MetroCatalogService metroCatalogService;

    public SupplierCatalogScheduler(GourmetSpiceService gourmetSpiceService, MetroCatalogService metroCatalogService) {
        this.gourmetSpiceService = gourmetSpiceService;
        this.metroCatalogService = metroCatalogService;
    }

    @Scheduled(cron = "${catalog.refresh.cron}", zone = "${catalog.refresh.zone}")
    public void refreshSupplierCatalogsAtShiftStart() {
        gourmetSpiceService.refreshNow();
        MetroScraperService.refreshNow();
        metroCatalogService.attachCatalogCodes(MetroScraperService.getCachedProductsAndRefresh());
    }
}
