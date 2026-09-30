package com.planify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
public class Course {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 120) private String title;
    @Column(length = 4000) private String description;
    @Column(nullable = false, unique = true, length = 12) private String code;
    @ManyToOne(optional = false) private Account instructor;
}
