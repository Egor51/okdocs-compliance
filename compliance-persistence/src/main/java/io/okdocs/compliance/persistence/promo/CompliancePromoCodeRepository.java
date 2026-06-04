package io.okdocs.compliance.persistence.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CompliancePromoCodeRepository extends JpaRepository<CompliancePromoCode, UUID> {

    /** Активный промокод по коду (без учёта регистра), не истёкший и с непревышенным лимитом. */
    @Query("""
            SELECT p FROM CompliancePromoCode p
            WHERE upper(p.code) = upper(:code)
              AND p.active = true
              AND (p.expiresAt IS NULL OR p.expiresAt > :now)
              AND (p.maxUses IS NULL OR p.usedCount < p.maxUses)
            """)
    Optional<CompliancePromoCode> findActiveByCodeIgnoreCase(@Param("code") String code,
                                                             @Param("now") Instant now);
}
