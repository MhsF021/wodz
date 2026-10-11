package br.com.wodz.config;

import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.domain.AppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * API stateless com JWT (HS256) emitido pela própria aplicação e validado
 * pelo resource server do Spring Security. Papéis: ORGANIZER e ATHLETE.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AppProperties props) throws Exception {
        http
                // Autenticação somente por Authorization: Bearer, sem cookies de autenticação.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/google").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/**", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/registrations/*/checkout",
                                "/api/webhooks/mercadopago", "/api/public/google/callback").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(props.isDev()))
                        .requestMatchers("/api/admin/**").hasRole("ORGANIZER")
                        .requestMatchers("/api/athlete/**").hasRole("ATHLETE")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecretKey jwtSecretKey(AppProperties props) {
        byte[] bytes = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret precisa ter pelo menos 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, AppUserRepository users, AppProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwt().issuer()), accountValidator(users)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> accountValidator(AppUserRepository users) {
        return jwt -> {
            try {
                String subject = jwt.getSubject();
                if (subject == null || !subject.matches("[1-9][0-9]{0,18}")) {
                    return invalidAccount();
                }
                if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())) {
                    return invalidAccount();
                }
                AppUser user = users.findById(Long.parseLong(subject)).orElse(null);
                // JWTs anteriores à migração valem somente enquanto a conta ainda está na versão zero.
                Object version = jwt.getClaim("token_version");
                if (version != null && !(version instanceof Number)) {
                    return invalidAccount();
                }
                long tokenVersion = version == null ? 0L : Long.parseLong(version.toString());
                if (user == null || user.isDeleted() || user.getRole() == null
                        || !user.getRole().name().equals(jwt.getClaimAsString("role"))
                        || tokenVersion != user.getTokenVersion()) {
                    return invalidAccount();
                }
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException ex) {
                return invalidAccount();
            }
        };
    }

    private static OAuth2TokenValidatorResult invalidAccount() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Sessão inválida ou revogada", null));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "ngrok-skip-browser-warning"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Somente este POST recebe o formulário Google com o cookie anti-CSRF.
        CorsConfiguration googleCallback = new CorsConfiguration();
        googleCallback.setAllowedOrigins(List.of("https://accounts.google.com"));
        googleCallback.setAllowedMethods(List.of("POST"));
        googleCallback.setAllowedHeaders(List.of("Content-Type"));
        googleCallback.setAllowCredentials(true);
        source.registerCorsConfiguration("/api/public/google/callback", googleCallback);
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
