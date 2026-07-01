package io.okdocs.compliance.persistence.billing;

import io.okdocs.compliance.contracts.enums.PricingPlanCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PricingPlanRepository extends JpaRepository<PricingPlan, Long> {

    List<PricingPlan> findByActiveTrueOrderBySortOrderAsc();

    Optional<PricingPlan> findByCodeAndActiveTrue(PricingPlanCode code);
}
