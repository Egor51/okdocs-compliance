package io.okdocs.compliance.worker.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.okdocs.compliance.contracts.crawler.PageAnalysisResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageAnalysisResultJsonCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void oldJsonWithoutPreConsentHostsDeserializesWithEmptyList() throws Exception {
        String json = """
                {
                  "url": "https://site.ru",
                  "title": "title",
                  "text": "text",
                  "html": "<html></html>",
                  "externalScriptDomains": ["mc.yandex.ru"],
                  "externalStyleDomains": [],
                  "internalLinks": [],
                  "cookiePresent": false,
                  "forms": [],
                  "renderMode": "STATIC"
                }
                """;

        PageAnalysisResult page = objectMapper.readValue(json, PageAnalysisResult.class);

        assertThat(page.preConsentTrackerHosts()).isEmpty();
    }
}
