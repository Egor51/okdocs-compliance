package io.okdocs.compliance.contracts.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Регистрация: создаёт юзера и его баланс сканов (квота по тарифу FREE).
 * Гостевые сканы к новому юзеру НЕ привязываются — гостевой скан урезан и эфемерен
 * (чистится по TTL). Для полноценного скана нужно запустить заново уже авторизованным.
 */
public record RegisterRequest(
        @Email @NotBlank String email,
        @Size(min = 8, max = 100) String password,
        String name,
        Boolean consentToProcessing
) {
}
