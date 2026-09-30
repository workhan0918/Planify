package com.planify;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"assignment_id", "student_id"}))
public class Submission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Assignment assignment;
    @ManyToOne(optional = false) private Account student;
    @Column(nullable = false) private String fileKey;
    @Column(nullable = false) private String fileName;
    @Column(nullable = false) private LocalDateTime submittedAt;
    private Integer score;
    @Column(length = 5000) private String feedback;
    private LocalDateTime gradedAt;
    @Version private Long version;
}
