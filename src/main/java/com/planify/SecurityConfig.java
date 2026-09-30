package com.planify;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(AccountRepository accounts) {
        return email -> accounts.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT))
            .map(a -> User.withUsername(a.getEmail()).password(a.getPassword()).roles(a.getRole().name()).build())
            .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다."));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/signup", "/css/**", "/js/**", "/error").permitAll()
                .requestMatchers("/instructor/**").hasRole("INSTRUCTOR")
                .requestMatchers("/student/**").hasRole("STUDENT")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").usernameParameter("email").defaultSuccessUrl("/", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .build();
    }
}
