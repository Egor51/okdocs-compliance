package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.SiteMonitorService;
import io.okdocs.compliance.contracts.monitoring.CreateSiteMonitorRequest;
import io.okdocs.compliance.contracts.monitoring.MonitorRunDto;
import io.okdocs.compliance.contracts.monitoring.SiteMonitorDto;
import io.okdocs.compliance.contracts.monitoring.UpdateSiteMonitorRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cabinet/monitors")
@RequiredArgsConstructor
public class SiteMonitorController {

    private final SiteMonitorService service;

    @GetMapping
    public List<SiteMonitorDto> list() {
        return service.list(CurrentPrincipal.require());
    }

    @PostMapping
    public ResponseEntity<SiteMonitorDto> create(
            @Valid @RequestBody CreateSiteMonitorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(request, CurrentPrincipal.require()));
    }

    @GetMapping("/{id}")
    public SiteMonitorDto get(@PathVariable UUID id) {
        return service.get(id, CurrentPrincipal.require());
    }

    @PatchMapping("/{id}")
    public SiteMonitorDto update(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateSiteMonitorRequest request) {
        return service.update(id, request, CurrentPrincipal.require());
    }

    @PostMapping("/{id}/pause")
    public SiteMonitorDto pause(@PathVariable UUID id) {
        return service.pause(id, CurrentPrincipal.require());
    }

    @PostMapping("/{id}/resume")
    public SiteMonitorDto resume(@PathVariable UUID id) {
        return service.resume(id, CurrentPrincipal.require());
    }

    @PostMapping("/{id}/run-now")
    public ResponseEntity<SiteMonitorDto> runNow(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.runNow(id, CurrentPrincipal.require()));
    }

    @GetMapping("/{id}/runs")
    public List<MonitorRunDto> runs(@PathVariable UUID id) {
        return service.runs(id, CurrentPrincipal.require());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id, CurrentPrincipal.require());
        return ResponseEntity.noContent().build();
    }
}
