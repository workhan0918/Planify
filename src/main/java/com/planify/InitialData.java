package com.planify;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InitialData implements CommandLineRunner {
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    @Value("${planify.instructor.email}") private String email;
    @Value("${planify.instructor.password}") private String password;
    @Value("${planify.instructor.name}") private String name;
    public InitialData(AccountRepository accounts, PasswordEncoder encoder) { this.accounts = accounts; this.encoder = encoder; }
    @Override @Transactional public void run(String... args) {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        accounts.findByEmail(normalized).ifPresentOrElse(a -> {
            if (a.getRole() != Account.Role.INSTRUCTOR) throw new IllegalStateException("초기 강사 이메일이 학생 계정과 중복됩니다.");
        }, () -> {
            Account a = new Account(); a.setEmail(normalized); a.setName(name);
            a.setPassword(encoder.encode(password)); a.setRole(Account.Role.INSTRUCTOR); accounts.save(a);
        });
    }
}
