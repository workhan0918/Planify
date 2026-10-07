package com.planify;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
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
    @Embedded private DevelopmentRecord development;
    private Boolean resubmissionAllowed;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "submission_rubric_scores", joinColumns = @JoinColumn(name = "submission_id"))
    @MapKeyColumn(name = "criterion_index") @Column(name = "points")
    private Map<Integer, Integer> rubricScores = new HashMap<>();
    public boolean isUnlocked() { return Boolean.TRUE.equals(resubmissionAllowed); }
    @Version private Long version;
}
