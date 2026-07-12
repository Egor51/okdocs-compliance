package io.okdocs.compliance.api.web;

import io.okdocs.compliance.api.security.CompliancePrincipal;
import io.okdocs.compliance.api.security.CurrentPrincipal;
import io.okdocs.compliance.api.service.AuthService;
import io.okdocs.compliance.contracts.auth.AuthMeResponse;
import io.okdocs.compliance.contracts.auth.AuthResponse;
import io.okdocs.compliance.contracts.auth.GuestAuthResponse;
import io.okdocs.compliance.contracts.auth.ForgotPasswordRequest;
import io.okdocs.compliance.contracts.auth.LoginRequest;
import io.okdocs.compliance.contracts.auth.OAuthExchangeRequest;
import io.okdocs.compliance.contracts.auth.RefreshTokenRequest;
import io.okdocs.compliance.contracts.auth.ResetPasswordRequest;
import io.okdocs.compliance.contracts.auth.RegisterRequest;
import io.okdocs.compliance.contracts.auth.UserProfileDto;
import io.okdocs.compliance.contracts.enums.PrincipalType;
import io.okdocs.compliance.persistence.auth.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Аутентификация: guest/register/login/refresh/logout/me (§4.1). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppUserRepository userRepository;
    private final ClientIpResolver clientIpResolver;
    private final io.okdocs.compliance.api.service.PasswordResetService passwordResetService;

    @PostMapping("/guest")
    public GuestAuthResponse guest() {
        return authService.issueGuestToken();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request, clientIpResolver.resolve(http),
                        http.getHeader(HttpHeaders.ACCEPT_LANGUAGE)));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest http) {
        passwordResetService.requestReset(request, clientIpResolver.resolve(http));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getHeader(HttpHeaders.USER_AGENT), clientIpResolver.resolve(http));
    }

    /**
     * Обмен one-time кода из OAuth-redirect (F.8) на JWT+refresh. Публичный: код сам — доказательство
     * прошедшего OAuth-флоу. Одноразовый и короткоживущий.
     */
    @PostMapping("/oauth/exchange")
    public AuthResponse exchangeOAuthCode(@Valid @RequestBody OAuthExchangeRequest request,
                                          HttpServletRequest http) {
        return authService.exchangeOAuthCode(request.code(),
                http.getHeader(HttpHeaders.USER_AGENT), clientIpResolver.resolve(http));
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest http) {
        return authService.refresh(request.refreshToken(),
                http.getHeader(HttpHeaders.USER_AGENT), clientIpResolver.resolve(http));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthMeResponse me() {
        return CurrentPrincipal.get()
                .map(this::describe)
                .orElseGet(() -> new AuthMeResponse(false, null, null, null));
    }

    private AuthMeResponse describe(CompliancePrincipal principal) {
        if (principal.type() == PrincipalType.GUEST) {
            return new AuthMeResponse(true, PrincipalType.GUEST, null, principal.guestId());
        }
        UserProfileDto profile = userRepository.findById(principal.userId())
                .map(AuthService::toProfile)
                .orElse(null);
        return new AuthMeResponse(profile != null, principal.type(), profile, null);
    }
}
