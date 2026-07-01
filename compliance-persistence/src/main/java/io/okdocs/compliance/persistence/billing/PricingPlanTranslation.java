package io.okdocs.compliance.persistence.billing;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Локализованные тексты pricing-продукта. */
@Entity
@Table(
        name = "pricing_plan_translations",
        uniqueConstraints = @UniqueConstraint(name = "uq_pricing_plan_translation_locale",
                columnNames = {"plan_id", "locale"}))
@Getter
@Setter
@NoArgsConstructor
public class PricingPlanTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private PricingPlan plan;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(name = "price_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "cta_label", nullable = false, length = 120)
    private String ctaLabel;

    @OneToMany(mappedBy = "translation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<PricingPlanFeature> features = new ArrayList<>();
}
