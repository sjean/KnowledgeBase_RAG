package com.example.aikb.service;

import com.example.aikb.entity.Role;
import com.example.aikb.entity.UserAccount;
import com.example.aikb.repository.UserAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrapService implements CommandLineRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrapService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        createIfMissing("admin", "admin123", Role.ADMIN);
        createIfMissing("user", "user123", Role.USER);
    }

    private void createIfMissing(String username, String rawPassword, Role role) {
        userAccountRepository.findByUsername(username).ifPresentOrElse(
                existing -> {
                },
                () -> {
                    UserAccount user = new UserAccount();
                    user.setUsername(username);
                    user.setPassword(passwordEncoder.encode(rawPassword));
                    user.setRole(role);
                    userAccountRepository.save(user);
                }
        );
    }
}
