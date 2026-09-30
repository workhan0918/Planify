package com.planify;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByEmail(String email);
    boolean existsByStudentNumber(String number);
}
interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByInstructorIdOrderByIdDesc(Long id);
    Optional<Course> findByCode(String code);
    boolean existsByCode(String code);
}
interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByCourseIdOrderByStudentStudentNumberAsc(Long id);
    List<Enrollment> findByStudentId(Long id);
    boolean existsByCourseIdAndStudentId(Long course, Long student);
}
interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findByCourseIdOrderByEndsAtAsc(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
interface SubmissionRepository extends JpaRepository<Submission, Long> {
    @Query("select s.assignment.id from Submission s where s.id = :id")
    Optional<Long> assignmentId(@org.springframework.data.repository.query.Param("id") Long id);
    Optional<Submission> findByAssignmentIdAndStudentId(Long assignment, Long student);
    List<Submission> findByAssignmentId(Long id);
    List<Submission> findByStudentId(Long id);
    void deleteByAssignmentId(Long id);
}
