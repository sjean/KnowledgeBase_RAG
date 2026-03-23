package com.example.aikb.service;

import com.example.aikb.dto.LoginRequest;
import com.example.aikb.dto.LoginResponse;
import com.example.aikb.dto.UserInfoResponse;
import com.example.aikb.entity.Role;
import com.example.aikb.entity.UserAccount;
import com.example.aikb.repository.UserAccountRepository;
import com.example.aikb.security.JwtService;
import com.example.aikb.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private JwtService jwtService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginShouldAuthenticateAndReturnJwtPayload() {
        AuthService authService = new AuthService(authenticationManager, userAccountRepository, jwtService);
        UserAccount user = new UserAccount();
        user.setId(7L);
        user.setUsername("user");
        user.setRole(Role.USER);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new TestingAuthenticationToken("user", "pwd"));
        when(userAccountRepository.findByUsername("user")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        LoginResponse response = authService.login(new LoginRequest("user", "pwd"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.username()).isEqualTo("user");
        assertThat(response.role()).isEqualTo("USER");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void loginShouldFailWhenUserCannotBeLoadedAfterAuthentication() {
        AuthService authService = new AuthService(authenticationManager, userAccountRepository, jwtService);
        when(userAccountRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "pwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User not found");
    }

    @Test
    void currentUserShouldReadPrincipalFromSecurityContext() {
        AuthService authService = new AuthService(authenticationManager, userAccountRepository, jwtService);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(new UserPrincipal(9L, "admin", "ADMIN"), null)
        );

        UserInfoResponse response = authService.currentUser();

        assertThat(response.userId()).isEqualTo(9L);
        assertThat(response.username()).isEqualTo("admin");
        assertThat(response.role()).isEqualTo("ADMIN");
    }
}
