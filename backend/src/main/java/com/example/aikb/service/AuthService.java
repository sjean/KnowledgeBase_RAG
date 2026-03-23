package com.example.aikb.service;

import com.example.aikb.dto.LoginRequest;
import com.example.aikb.dto.LoginResponse;
import com.example.aikb.dto.UserInfoResponse;
import com.example.aikb.entity.UserAccount;
import com.example.aikb.repository.UserAccountRepository;
import com.example.aikb.security.JwtService;
import com.example.aikb.util.SecurityUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository userAccountRepository;
    private final JwtService jwtService;

    public AuthService(AuthenticationManager authenticationManager,
                       UserAccountRepository userAccountRepository,
                       JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userAccountRepository = userAccountRepository;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserAccount user = userAccountRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String token = jwtService.generateToken(user);
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole().name());
    }

    public UserInfoResponse currentUser() {
        var principal = SecurityUtils.currentUser();
        return new UserInfoResponse(principal.userId(), principal.username(), principal.role());
    }
}
