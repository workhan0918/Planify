package com.planify;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"submission_id", "revision_number"}))
public class SubmissionRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Submission submission;
    @Column(nullable = false) private int revisionNumber;
    @Column(nullable = false) private String fileKey;
    @Column(nullable = false) private String fileName;
    @Column(nullable = false) private LocalDateTime submittedAt;
    private Integer score;
    @Column(length = 5000) private String feedback;
    private LocalDateTime gradedAt;
    private int maxScore;
    @Column(length = 10000) private String rubricSummary;
    @Embedded private DevelopmentRecord development;
}
