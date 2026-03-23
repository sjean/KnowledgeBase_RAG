package com.example.aikb.security;

import com.example.aikb.config.AppProperties;
import com.example.aikb.entity.Role;
import com.example.aikb.entity.UserAccount;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret("12345678901234567890123456789012");
        properties.getJwt().setExpirationHours(2);
        jwtService = new JwtService(properties);
    }

    @Test
    void generateTokenShouldContainExpectedClaims() {
        UserAccount user = new UserAccount();
        user.setId(42L);
        user.setUsername("alice");
        user.setRole(Role.ADMIN);

        String token = jwtService.generateToken(user);

        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.parse(token).get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void parseShouldRejectInvalidToken() {
        assertThatThrownBy(() -> jwtService.parse("not-a-valid-token"))
                .isInstanceOf(JwtException.class);
    }
}
