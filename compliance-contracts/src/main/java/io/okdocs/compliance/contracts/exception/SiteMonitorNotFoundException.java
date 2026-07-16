package io.okdocs.compliance.contracts.exception;

import java.util.UUID;

public class SiteMonitorNotFoundException extends RuntimeException {
    public SiteMonitorNotFoundException(UUID id) {
        super("Site monitor not found: " + id);
    }
}
