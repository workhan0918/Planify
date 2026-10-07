package com.planify;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Account recipient;
    @Column(nullable = false, length = 250) private String message;
    private Long assignmentId;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
