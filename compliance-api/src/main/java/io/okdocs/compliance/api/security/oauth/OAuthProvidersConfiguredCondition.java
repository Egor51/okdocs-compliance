package io.okdocs.compliance.api.security.oauth;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Условие «соц-логин настроен»: истинно, только если в {@code compliance.oauth.client.providers.*}
 * есть хотя бы один провайдер с непустыми client-id И client-secret.
 * <p>
 * Нужно именно {@link Condition} (а не проверка внутри {@code @Bean}): метод-фабрика, возвращающий
 * {@code null}, всё равно регистрирует bean DEFINITION, и {@code @ConditionalOnBean(
 * ClientRegistrationRepository)} в {@code SecurityConfig} включил бы OAuth-цепочку по «фантомному»
 * bean'у. {@code @Conditional} предотвращает само создание определения, когда провайдеров нет.
 */
public class OAuthProvidersConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        OAuthClientProperties props = Binder.get(context.getEnvironment())
                .bind("compliance.oauth.client", OAuthClientProperties.class)
                .orElseGet(() -> new OAuthClientProperties(null));
        return props.providers().values().stream()
                .anyMatch(c -> c != null && c.isConfigured());
    }
}
