package io.okdocs.compliance.persistence.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Один локализованный пункт списка возможностей тарифа. */
@Entity
@Table(
        name = "pricing_plan_features",
        uniqueConstraints = @UniqueConstraint(name = "uq_pricing_plan_feature_order",
                columnNames = {"translation_id", "sort_order"}))
@Getter
@Setter
@NoArgsConstructor
public class PricingPlanFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "translation_id", nullable = false)
    private PricingPlanTranslation translation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
