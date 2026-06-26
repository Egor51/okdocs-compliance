package io.okdocs.compliance.worker.service;

import io.okdocs.compliance.rules.Rule;
import io.okdocs.compliance.rules.RuleDefinition;
import io.okdocs.compliance.rules.RuleEngine;
import io.okdocs.compliance.rules.eu.EuCommonMetadata;
import io.okdocs.compliance.rules.eu.EuControllerIdentityMissingRule;
import io.okdocs.compliance.rules.eu.EuDataSubjectRightsMissingRule;
import io.okdocs.compliance.rules.eu.EuPrivacyNoticeMissingRule;
import io.okdocs.compliance.rules.eu.EuThirdCountryTrackerRiskRule;
import io.okdocs.compliance.rules.ru.ConsentDefaultCheckedRule;
import io.okdocs.compliance.rules.common.CookieWithoutSecureFlagRule;
import io.okdocs.compliance.rules.ru.CrossBorderTransferRule;
import io.okdocs.compliance.rules.ru.DnsCnameToForeignCloudRule;
import io.okdocs.compliance.rules.ru.DnsForeignMailProviderRule;
import io.okdocs.compliance.rules.ru.DnsForeignWebInfrastructureRule;
import io.okdocs.compliance.rules.ru.DnsLookupFailedRule;
import io.okdocs.compliance.rules.ru.DnsMulticountryHostingRule;
import io.okdocs.compliance.rules.ru.ForeignAuthProviderRule;
import io.okdocs.compliance.rules.ru.HttpFormActionRule;
import io.okdocs.compliance.rules.common.HttpsNotEnforcedRule;
import io.okdocs.compliance.rules.common.LocalStorageTrackingBeforeConsentRule;
import io.okdocs.compliance.rules.common.MissingCspRule;
import io.okdocs.compliance.rules.ru.MissingFrameProtectionRule;
import io.okdocs.compliance.rules.common.MissingHstsRule;
import io.okdocs.compliance.rules.ru.MissingReferrerPolicyRule;
import io.okdocs.compliance.rules.ru.MissingXContentTypeOptionsRule;
import io.okdocs.compliance.rules.common.MixedContentRule;
import io.okdocs.compliance.rules.ru.NoCookieConsentRule;
import io.okdocs.compliance.rules.ru.NoOperatorContactsRule;
import io.okdocs.compliance.rules.ru.NoPrivacyPolicyRule;
import io.okdocs.compliance.rules.ru.NonRuHostingRule;
import io.okdocs.compliance.rules.ru.NotInRknRegistryRule;
import io.okdocs.compliance.rules.ru.SensitivePageCacheableRule;
import io.okdocs.compliance.rules.common.SessionCookieWithoutHttpOnlyRule;
import io.okdocs.compliance.rules.ru.TechStackDisclosureRule;
import io.okdocs.compliance.rules.common.TlsCertificateExpiresSoonRule;
import io.okdocs.compliance.rules.common.TlsCertificateInvalidRule;
import io.okdocs.compliance.rules.ru.TlsHostnameMismatchRule;
import io.okdocs.compliance.rules.ru.TlsLegacyProtocolRule;
import io.okdocs.compliance.rules.ru.ThirdPartyTrackersRule;
import io.okdocs.compliance.rules.ru.TrackersBeforeConsentRule;
import io.okdocs.compliance.rules.common.TrackingCookiesBeforeConsentRule;
import io.okdocs.compliance.rules.ru.UnprotectedDataFormsRule;
import io.okdocs.compliance.rules.common.WeakCspRule;
import io.okdocs.compliance.rules.common.WildcardCorsRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Сборка правил (§5.1, multi-layer § PLAN-jurisdictions). Правила в {@code compliance-rules} НЕ
 * аннотированы {@code @Component} (модуль без Spring), поэтому autowire {@code List<Rule>} собрал бы
 * пустой список — бины создаются вручную явной фабрикой. Один общий список {@code rules()} вместо
 * {@code @Bean} на правило: гейт по слоям юрисдикции делает {@link RuleEngine}, а не конфигурация.
 * <p>
 * Группировка: RU-специфичные правила (152-ФЗ-эвристики: политика/реквизиты/РКН/хостинг) живут в
 * {@code rules.ru} и применимы только к слою RU. Reusable technical-правила
 * (headers/TLS/cookies — jurisdiction-neutral детектор) вынесены в {@code rules.common} с
 * {@code supportedLayers = {RU, EU, UK}}: один детектор, per-layer метаданные резолвятся отдельно.
 */
@Configuration
public class RulesConfiguration {

    @Bean
    List<Rule> rules() {
        return List.of(
                // ── RU-специфичные (слой RU): 152-ФЗ-эвристики, не переносимы на EU/UK ──────────
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
                // RU-специфичные security/transport/DNS (привязаны к 152-ФЗ-формулировкам/хостингу).
                new MissingReferrerPolicyRule(),
                new MissingXContentTypeOptionsRule(),
                new MissingFrameProtectionRule(),
                new SensitivePageCacheableRule(),
                new TechStackDisclosureRule(),
                new HttpFormActionRule(),
                new TlsHostnameMismatchRule(),
                new TlsLegacyProtocolRule(),
                new DnsForeignWebInfrastructureRule(),
                new DnsForeignMailProviderRule(),
                new DnsCnameToForeignCloudRule(),
                new DnsMulticountryHostingRule(),
                new DnsLookupFailedRule(),
                // ── Common (слои {RU, EU, UK}): jurisdiction-neutral технические детекторы ───────
                // Метаданные own-слоя — RU; EU/UK накладываются standalone-записями (Фаза 3).
                new MissingHstsRule(),
                new MissingCspRule(),
                new WeakCspRule(),
                new WildcardCorsRule(),
                new HttpsNotEnforcedRule(),
                new MixedContentRule(),
                new TlsCertificateInvalidRule(),
                new TlsCertificateExpiresSoonRule(),
                new TrackingCookiesBeforeConsentRule(),
                new LocalStorageTrackingBeforeConsentRule(),
                new CookieWithoutSecureFlagRule(),
                new SessionCookieWithoutHttpOnlyRule(),
                // ── EU baseline (слой EU): статические GDPR-правила, работают на EU/DE/FR/ES ─────
                // Consent-зависимые EU-правила (NO_REJECT_OPTION и т.п.) — Фаза 5 (consent-краулер).
                new EuPrivacyNoticeMissingRule(),
                new EuControllerIdentityMissingRule(),
                new EuDataSubjectRightsMissingRule(),
                new EuThirdCountryTrackerRiskRule());
    }

    @Bean
    RuleEngine ruleEngine(List<Rule> rules) {
        return new RuleEngine(rules);
    }

    /**
     * Резолвер метаданных: own-метаданные правил + EU-overlay для shared common-кодов
     * ({@link EuCommonMetadata}). Создаётся вручную (не autowire), чтобы передать overlay вторым
     * аргументом. EU-overlay даёт common-детекторам GDPR-обоснование на EU/DE/FR/ES-сканах.
     */
    @Bean
    RuleMetadataResolver ruleMetadataResolver(List<Rule> rules) {
        List<RuleDefinition> overlayMetadata = EuCommonMetadata.entries();
        return new RuleMetadataResolver(rules, overlayMetadata);
    }

}
