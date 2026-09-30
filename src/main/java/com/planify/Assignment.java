package com.planify;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
public class Assignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Course course;
    @Column(nullable = false, length = 150) private String title;
    @Column(nullable = false, length = 10000) private String description;
    @Column(nullable = false) private LocalDateTime startsAt;
    @Column(nullable = false) private LocalDateTime endsAt;
    @Column(nullable = false) private int maxScore;
    private String attachmentKey;
    private String attachmentName;
    @Version private Long version;
    public String statusAt(LocalDateTime now) {
        return now.isBefore(startsAt) ? "예정" : now.isAfter(endsAt) ? "마감" : "진행중";
    }
    public boolean isOpen(LocalDateTime now) { return !now.isBefore(startsAt) && !now.isAfter(endsAt); }
}
