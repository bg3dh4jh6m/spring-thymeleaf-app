package com.example.demo.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetroScraperServiceTest {

    private final MetroScraperService service = new MetroScraperService();

    @Test
    void calculatesMultipackPriceFromTotalWeight() {
        String fullName = "Fine Life Черен пипер млян 10 бр x 10 г / 100 г";

        assertThat(service.extractWeight(fullName)).isEqualTo("10 бр × 10 г / 100 г");
        assertThat(service.extractPackageWeight(fullName)).isEqualTo("10 г");
        assertThat(service.extractPackageCount(fullName, "1 ПАКЕТ")).isEqualTo("10 бр.");
        assertThat(service.calculatePricePerKg("1,99 €", fullName)).isEqualTo("19,90 €/кг");
        assertThat(service.cleanProductName(fullName, "Fine Life")).isEqualTo("Черен пипер млян");
    }
}
