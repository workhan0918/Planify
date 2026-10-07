package com.planify;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
public class UnlockAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Submission submission;
    @ManyToOne(optional = false) private Account instructor;
    @Column(nullable = false, length = 500) private String reason;
    @Column(nullable = false) private LocalDateTime unlockedAt;
}
