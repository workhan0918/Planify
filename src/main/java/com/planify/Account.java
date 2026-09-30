package com.planify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "accounts") @Getter @Setter
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true, length = 30) private String studentNumber;
    @Column(nullable = false, length = 80) private String name;
    @Column(nullable = false, unique = true, length = 150) private String email;
    @Column(nullable = false) private String password;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    public enum Role { INSTRUCTOR, STUDENT }
}
