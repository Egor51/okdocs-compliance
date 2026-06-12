package io.okdocs.compliance.contracts.scan;

import io.okdocs.compliance.contracts.enums.EvidenceType;
import io.okdocs.compliance.contracts.enums.SourceType;
import io.okdocs.compliance.contracts.enums.VerificationStatus;

import java.util.List;

/** Страница, на которой зафиксировано конкретное нарушение, с локальным доказательством. */
public record AffectedPageDto(
        String url,
        String evidence,
        SourceType sourceType,
        Double confidence,
        VerificationStatus verificationStatus,
        EvidenceType evidenceType,
        List<String> matchedSignals
) {
}
