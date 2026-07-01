package io.okdocs.compliance.api.service;

import io.okdocs.compliance.contracts.enums.BillingPeriod;
import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import io.okdocs.compliance.persistence.billing.PricingPlan;
import io.okdocs.compliance.persistence.billing.PricingPlanFeature;
import io.okdocs.compliance.persistence.billing.PricingPlanRepository;
import io.okdocs.compliance.persistence.billing.PricingPlanTranslation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingPlanCatalogServiceTest {

    @Mock private PricingPlanRepository repository;

    private PricingPlanCatalogService service;

    @BeforeEach
    void setUp() {
        service = new PricingPlanCatalogService(repository);
    }

    @Test
    void listReturnsLocalizedPlansWithFeatureOrder() {
        PricingPlan pro = plan(PricingPlanCode.PRO, 20, true);
        translation(pro, "ru", "4990.00", "RUB", "PRO RU", "Описание", "Выбрать",
                feature("Второй", 20), feature("Первый", 10));
        translation(pro, "en", "79.00", "USD", "PRO EN", "Description", "Choose",
                feature("First", 10));
        when(repository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(pro));

        var response = service.list("ru-RU");

        assertThat(response.items()).hasSize(1);
        var dto = response.items().getFirst();
        assertThat(dto.code()).isEqualTo(PricingPlanCode.PRO);
        assertThat(dto.displayName()).isEqualTo("PRO RU");
        assertThat(dto.priceAmount()).isEqualByComparingTo("4990.00");
        assertThat(dto.currency()).isEqualTo("RUB");
        assertThat(dto.features()).containsExactly("Первый", "Второй");
        assertThat(dto.highlighted()).isTrue();
        assertThat(dto.sortOrder()).isEqualTo(20);
    }

    @Test
    void listFallsBackToEnglishWhenRequestedLocaleIsMissing() {
        PricingPlan oneReport = plan(PricingPlanCode.ONE_REPORT, 10, false);
        translation(oneReport, "en", "19.00", "USD", "1 report", "Description", "Buy",
                feature("Full report", 10));
        when(repository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(oneReport));

        var response = service.list("de");

        assertThat(response.items()).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.displayName()).isEqualTo("1 report");
                    assertThat(dto.priceAmount()).isEqualByComparingTo("19.00");
                    assertThat(dto.currency()).isEqualTo("USD");
                    assertThat(dto.ctaLabel()).isEqualTo("Buy");
                });
    }

    @Test
    void findReturnsEmptyForNullCode() {
        assertThat(service.find(null, "ru")).isEmpty();
    }

    @Test
    void findMapsSinglePlan() {
        PricingPlan business = plan(PricingPlanCode.BUSINESS, 30, false);
        translation(business, "ru", "19900.00", "RUB", "BUSINESS", "Для компаний", "Выбрать",
                feature("200 отчётов", 10));
        when(repository.findByCodeAndActiveTrue(PricingPlanCode.BUSINESS)).thenReturn(Optional.of(business));

        var dto = service.find(PricingPlanCode.BUSINESS, "ru").orElseThrow();

        assertThat(dto.code()).isEqualTo(PricingPlanCode.BUSINESS);
        assertThat(dto.billingPeriod()).isEqualTo(BillingPeriod.MONTH);
        assertThat(dto.priceAmount()).isEqualByComparingTo("19900.00");
        assertThat(dto.currency()).isEqualTo("RUB");
        assertThat(dto.features()).containsExactly("200 отчётов");
    }

    private static PricingPlan plan(PricingPlanCode code, int sortOrder, boolean highlighted) {
        PricingPlan plan = new PricingPlan();
        plan.setCode(code);
        plan.setActive(true);
        plan.setBillingPeriod(code == PricingPlanCode.ONE_REPORT ? BillingPeriod.ONE_TIME : BillingPeriod.MONTH);
        plan.setIncludedReports(code == PricingPlanCode.ONE_REPORT ? 1 : 30);
        plan.setHighlighted(highlighted);
        plan.setSortOrder(sortOrder);
        return plan;
    }

    private static PricingPlanTranslation translation(PricingPlan plan, String locale, String priceAmount,
                                                      String currency, String name,
                                                      String description, String cta,
                                                      PricingPlanFeature... features) {
        PricingPlanTranslation translation = new PricingPlanTranslation();
        translation.setPlan(plan);
        translation.setLocale(locale);
        translation.setPriceAmount(new BigDecimal(priceAmount));
        translation.setCurrency(currency);
        translation.setDisplayName(name);
        translation.setDescription(description);
        translation.setCtaLabel(cta);
        for (PricingPlanFeature feature : features) {
            feature.setTranslation(translation);
            translation.getFeatures().add(feature);
        }
        plan.getTranslations().add(translation);
        return translation;
    }

    private static PricingPlanFeature feature(String text, int sortOrder) {
        PricingPlanFeature feature = new PricingPlanFeature();
        feature.setText(text);
        feature.setSortOrder(sortOrder);
        return feature;
    }
}
