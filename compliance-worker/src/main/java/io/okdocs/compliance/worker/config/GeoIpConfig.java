package io.okdocs.compliance.worker.config;

import com.maxmind.geoip2.DatabaseReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Поднимает {@link DatabaseReader} MaxMind/db-ip из {@code compliance.geoip.db-path}.
 * Используется {@link io.okdocs.compliance.worker.service.HostCountryDetector} для определения
 * страны хостинга по IP (§5.5).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GeoIpConfig {

    private final ComplianceWorkerProperties properties;

    @Bean
    public DatabaseReader geoIpDatabaseReader() throws IOException {
        String dbPath = properties.getGeoip().getDbPath();
        ResourceLoader loader = new DefaultResourceLoader();
        Resource resource = loader.getResource(dbPath);
        try (InputStream is = resource.getInputStream()) {
            DatabaseReader reader = new DatabaseReader.Builder(is).build();
            log.info("GeoIP database loaded from: {}", dbPath);
            return reader;
        }
    }
}
