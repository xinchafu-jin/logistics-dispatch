package com.example.backend.config;

import com.example.backend.service.AuthService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
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

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/admin/login",
                                "/api/auth/admin/forgot-password/verify",
                                "/api/auth/admin/forgot-password/reset",
                                "/api/auth/driver/login",
                                "/api/auth/driver/forgot-password/verify",
                                "/api/auth/driver/forgot-password/reset"
                        ).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/uploads/driver-photos/**", "/uploads/delivery-photos/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/driver-account-applications").permitAll()
                        .requestMatchers("/api/driver/**")
                        .hasRole(AuthService.ROLE_DRIVER)
                        .requestMatchers("/api/driver-schedules/**", "/api/fleet/**", "/api/emergency-leave-requests/**")
                        .hasRole(AuthService.ROLE_ADMIN)
                        .requestMatchers("/api/admin-users/**", "/api/drivers/**", "/api/warehouses/**", "/api/stores/**",
                                "/api/vehicles/**", "/api/orders/**", "/api/dispatch/**", "/api/fuel-prices/**",
                                "/api/ai/**", "/api/reports/**", "/api/driver-account-applications/**",
                                "/api/exceptions/**", "/api/geocode/**")
                        .hasRole(AuthService.ROLE_ADMIN)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 必須至少 32 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey secretKey) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AuthService.TOKEN_ISSUER));
        return jwtDecoder;
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey secretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return authenticationConverter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:4200",
                "http://127.0.0.1:4200",
                "http://localhost:4001",
                "http://127.0.0.1:4001",
                "http://localhost:4204",
                "http://127.0.0.1:4204"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
    // api key
    @Bean
    public TextEncryptor aiApiKeyEncryptor(

            @Value("${app.crypto.password}")String password,
            @Value("${app.crypto.salt}")String salt
    ){
        if (password.length() < 32) {
            throw new IllegalStateException("APP_CRYPTO_PASSWORD 必須至少 32 字元");
        }
        if (!salt.matches("^[0-9a-fA-F]{16,}$") || salt.length() % 2 != 0) {
            throw new IllegalStateException("APP_CRYPTO_SALT 必須是偶數長度的 16 進位字串，至少 16 字元");
        }
        return Encryptors.delux(password, salt);
    }

}
