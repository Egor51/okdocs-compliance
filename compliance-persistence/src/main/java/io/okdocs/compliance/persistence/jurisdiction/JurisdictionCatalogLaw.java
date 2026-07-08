package io.okdocs.compliance.persistence.jurisdiction;

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

/** Один закон/акт в списке проверяемых для юрисдикции (например «GDPR», «BDSG»). */
@Entity
@Table(
        name = "jurisdiction_catalog_laws",
        uniqueConstraints = @UniqueConstraint(name = "uq_jurisdiction_law_order",
                columnNames = {"jurisdiction_id", "sort_order"}))
@Getter
@Setter
@NoArgsConstructor
public class JurisdictionCatalogLaw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jurisdiction_id", nullable = false)
    private JurisdictionCatalog jurisdiction;

    @Column(nullable = false, length = 120)
    private String text;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
