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

    @Test
    void calculatesLiterPriceAndMeasureForLiquidProduct() {
        String fullName = "YAMASA Соев сос 18л";

        assertThat(service.extractPackageWeight(fullName)).isEqualTo("18 л");
        assertThat(service.extractTotalPackageSize(fullName)).isEqualTo("18 л");
        assertThat(service.extractPackageCount(fullName, "1 БРОЙ")).isEqualTo("1 бр.");
        assertThat(service.detectMeasureUnit(fullName)).isEqualTo("л");
        assertThat(service.calculatePricePerKg("81,30 €", fullName)).isEmpty();
        assertThat(service.calculatePricePerLiter("81,30 €", fullName)).isEqualTo("4,52 €/л");
        assertThat(service.cleanProductName(fullName, "YAMASA")).isEqualTo("Соев сос");
    }

    @Test
    void calculatesTotalVolumeForMultipack() {
        String fullName = "Сос 6 бр x 250 мл / 1500 мл";

        assertThat(service.extractPackageWeight(fullName)).isEqualTo("250 мл");
        assertThat(service.extractTotalPackageSize(fullName)).isEqualTo("1500 мл");
        assertThat(service.extractPackageCount(fullName, "1 ПАКЕТ")).isEqualTo("6 бр.");
        assertThat(service.calculatePricePerLiter("9,00 €", fullName)).isEqualTo("6,00 €/л");
    }
}
