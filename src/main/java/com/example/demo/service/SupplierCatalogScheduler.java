package com.example.demo.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SupplierCatalogScheduler {

    private final GourmetSpiceService gourmetSpiceService;

    public SupplierCatalogScheduler(GourmetSpiceService gourmetSpiceService) {
        this.gourmetSpiceService = gourmetSpiceService;
    }

    @Scheduled(cron = "${catalog.refresh.cron}", zone = "${catalog.refresh.zone}")
    public void refreshSupplierCatalogsAtShiftStart() {
        gourmetSpiceService.refreshNow();
        MetroScraperService.refreshNow();
    }
}
