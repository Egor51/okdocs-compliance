package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.service.RemediationLeadService;
import io.okdocs.compliance.contracts.remediation.RemediationLeadRequest;
import io.okdocs.compliance.contracts.remediation.RemediationLeadResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/remediation-requests")
@RequiredArgsConstructor
public class RemediationLeadController {

    private final RemediationLeadService remediationLeadService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RemediationLeadResponse create(@Valid @RequestBody RemediationLeadRequest request,
                                          HttpServletRequest httpRequest) {
        return remediationLeadService.create(request, clientIpResolver.resolve(httpRequest));
    }
}
