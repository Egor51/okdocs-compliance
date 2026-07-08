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

/** Локализованные тексты юрисдикции: H1/H2 лендинга, SEO-мета и название страны. */
@Entity
@Table(
        name = "jurisdiction_catalog_translations",
        uniqueConstraints = @UniqueConstraint(name = "uq_jurisdiction_translation_locale",
                columnNames = {"jurisdiction_id", "locale"}))
@Getter
@Setter
@NoArgsConstructor
public class JurisdictionCatalogTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jurisdiction_id", nullable = false)
    private JurisdictionCatalog jurisdiction;

    @Column(nullable = false, length = 16)
    private String locale;

    /** H1 на лендинге юрисдикции. */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** H2 (подзаголовок) на лендинге. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "seo_title", nullable = false)
    private String seoTitle;

    @Column(name = "seo_description", nullable = false, columnDefinition = "TEXT")
    private String seoDescription;

    @Column(name = "country_name", nullable = false, length = 120)
    private String countryName;
}
