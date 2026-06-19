package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.ru.ConsentDefaultCheckedRule;
import io.okdocs.compliance.rules.ru.CookieWithoutSecureFlagRule;
import io.okdocs.compliance.rules.ru.CrossBorderTransferRule;
import io.okdocs.compliance.rules.ru.DnsCnameToForeignCloudRule;
import io.okdocs.compliance.rules.ru.DnsForeignMailProviderRule;
import io.okdocs.compliance.rules.ru.DnsForeignWebInfrastructureRule;
import io.okdocs.compliance.rules.ru.DnsLookupFailedRule;
import io.okdocs.compliance.rules.ru.DnsMulticountryHostingRule;
import io.okdocs.compliance.rules.ru.ForeignAuthProviderRule;
import io.okdocs.compliance.rules.ru.HttpFormActionRule;
import io.okdocs.compliance.rules.ru.HttpsNotEnforcedRule;
import io.okdocs.compliance.rules.ru.LocalStorageTrackingBeforeConsentRule;
import io.okdocs.compliance.rules.ru.MissingCspRule;
import io.okdocs.compliance.rules.ru.MissingFrameProtectionRule;
import io.okdocs.compliance.rules.ru.MissingHstsRule;
import io.okdocs.compliance.rules.ru.MissingReferrerPolicyRule;
import io.okdocs.compliance.rules.ru.MissingXContentTypeOptionsRule;
import io.okdocs.compliance.rules.ru.MixedContentRule;
import io.okdocs.compliance.rules.ru.NoCookieConsentRule;
import io.okdocs.compliance.rules.ru.NoOperatorContactsRule;
import io.okdocs.compliance.rules.ru.NoPrivacyPolicyRule;
import io.okdocs.compliance.rules.ru.NonRuHostingRule;
import io.okdocs.compliance.rules.ru.NotInRknRegistryRule;
import io.okdocs.compliance.rules.ru.SensitivePageCacheableRule;
import io.okdocs.compliance.rules.ru.SessionCookieWithoutHttpOnlyRule;
import io.okdocs.compliance.rules.ru.TechStackDisclosureRule;
import io.okdocs.compliance.rules.ru.TlsCertificateExpiresSoonRule;
import io.okdocs.compliance.rules.ru.TlsCertificateInvalidRule;
import io.okdocs.compliance.rules.ru.TlsHostnameMismatchRule;
import io.okdocs.compliance.rules.ru.TlsLegacyProtocolRule;
import io.okdocs.compliance.rules.ru.ThirdPartyTrackersRule;
import io.okdocs.compliance.rules.ru.TrackersBeforeConsentRule;
import io.okdocs.compliance.rules.ru.TrackingCookiesBeforeConsentRule;
import io.okdocs.compliance.rules.ru.UnprotectedDataFormsRule;
import io.okdocs.compliance.rules.ru.WeakCspRule;
import io.okdocs.compliance.rules.ru.WildcardCorsRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Сборка правил (§5.1). Правила в {@code compliance-rules} НЕ аннотированы {@code @Component}
 * (модуль без Spring), поэтому autowire {@code List<Rule>} собрал бы пустой список — бины
 * создаются вручную явной фабрикой. Один список вместо {@code @Bean} на правило: легко
 * фильтровать по юрисдикции (ru/eu) и держать {@code compliance-rules} чистым от Spring.
 */
@Configuration
public class RulesConfiguration {

    @Bean
    List<Rule> ruRules() {
        return List.of(
                new NoPrivacyPolicyRule(),
                new UnprotectedDataFormsRule(),
                new ConsentDefaultCheckedRule(),
                new NoCookieConsentRule(),
                new ThirdPartyTrackersRule(),
                new CrossBorderTransferRule(),
                new ForeignAuthProviderRule(),
                new NoOperatorContactsRule(),
                new NotInRknRegistryRule(),
                new NonRuHostingRule(),
                new TrackersBeforeConsentRule(),
                // Этап 1 technical-rules: security headers (SECURITY, ст. 19 152-ФЗ + OWASP).
                new MissingHstsRule(),
                new MissingCspRule(),
                new WeakCspRule(),
                new MissingReferrerPolicyRule(),
                new MissingXContentTypeOptionsRule(),
                new MissingFrameProtectionRule(),
                new WildcardCorsRule(),
                new SensitivePageCacheableRule(),
                new TechStackDisclosureRule(),
                // Этап 2 technical-rules: TLS + транспорт (SECURITY, ст. 19 152-ФЗ + OWASP TLS).
                new HttpsNotEnforcedRule(),
                new HttpFormActionRule(),
                new MixedContentRule(),
                new TlsCertificateInvalidRule(),
                new TlsCertificateExpiresSoonRule(),
                new TlsHostnameMismatchRule(),
                new TlsLegacyProtocolRule(),
                // Этап 3 technical-rules: DNS-инфраструктура (HOSTING, ч. 5 ст. 18 / ст. 12 152-ФЗ).
                new DnsForeignWebInfrastructureRule(),
                new DnsForeignMailProviderRule(),
                new DnsCnameToForeignCloudRule(),
                new DnsMulticountryHostingRule(),
                new DnsLookupFailedRule(),
                // Этап 4 technical-rules (Cookies Phase 1): pre-consent cookies/storage (COOKIES,
                // ст. 6/9/19 152-ФЗ + ст. 13.11 КоАП). Reject/accept-сценарии — отдельный Phase 2.
                new TrackingCookiesBeforeConsentRule(),
                new LocalStorageTrackingBeforeConsentRule(),
                new CookieWithoutSecureFlagRule(),
                new SessionCookieWithoutHttpOnlyRule());
    }

    @Bean
    RuleEngine ruleEngine(List<Rule> ruRules) {
        return new RuleEngine(ruRules);
    }
    
}
