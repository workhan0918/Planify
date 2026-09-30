package com.planify;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Getter @Setter
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "student_id"}))
public class Enrollment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private Course course;
    @ManyToOne(optional = false) private Account student;
}
