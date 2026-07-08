package io.okdocs.compliance.persistence.jurisdiction;

import io.okdocs.compliance.contracts.enums.ScanJurisdiction;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Каталог юрисдикций, показываемых на публичном фронте. */
@Entity
@Table(name = "jurisdiction_catalog")
@Getter
@Setter
@NoArgsConstructor
public class JurisdictionCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, unique = true)
    private ScanJurisdiction code;

    @Column(nullable = false, length = 64, unique = true)
    private String slug;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "content_language", nullable = false, length = 8)
    private String contentLanguage;

    @Column(name = "default_jurisdiction", nullable = false)
    private boolean defaultJurisdiction;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    // @BatchSize вместо @EntityGraph/join fetch: обе коллекции — bag (List), одновременный
    // fetch двух bag'ов Hibernate запрещает (MultipleBagFetchException). Батч убирает N+1
    // каталога: laws/translations всех юрисдикций грузятся одним IN-запросом на коллекцию.
    @OneToMany(mappedBy = "jurisdiction", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 32)
    private List<JurisdictionCatalogTranslation> translations = new ArrayList<>();

    @OneToMany(mappedBy = "jurisdiction", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 32)
    private List<JurisdictionCatalogLaw> laws = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
