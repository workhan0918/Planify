package com.planify;

import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service @Transactional(readOnly = true)
public class PlanifyService {
    private final AccountRepository accounts;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final AssignmentRepository assignments;
    private final SubmissionRepository submissions;
    private final PasswordEncoder encoder;
    private final FileStorage files;
    private final Clock clock;
    public PlanifyService(AccountRepository accounts, CourseRepository courses, EnrollmentRepository enrollments,
        AssignmentRepository assignments, SubmissionRepository submissions, PasswordEncoder encoder, FileStorage files, Clock clock) {
        this.accounts = accounts; this.courses = courses; this.enrollments = enrollments;
        this.assignments = assignments; this.submissions = submissions; this.encoder = encoder; this.files = files; this.clock = clock;
    }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public List<Enrollment> students(Long courseId, Account a) { role(a, Account.Role.INSTRUCTOR); course(courseId, a); return enrollments.findByCourseIdOrderByStudentStudentNumberAsc(courseId); }
    public List<Assignment> tasks(Long courseId, Account a) { course(courseId, a); return assignments.findByCourseIdOrderByEndsAtAsc(courseId); }
    public Submission ownSubmission(Long taskId, Account a) { assignment(taskId, a); return submissions.findByAssignmentIdAndStudentId(taskId, a.getId()).orElse(null); }
    public Account account(String email) { return accounts.findByEmail(email).orElseThrow(() -> new AccessDeniedException("로그인이 필요합니다.")); }
    private void role(Account a, Account.Role role) { if (a.getRole() != role) throw new AccessDeniedException("접근 권한이 없습니다."); }
    @Transactional public void signup(Forms.Signup form) {
        if (form.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new RuleException("비밀번호는 UTF-8 기준 최대 72바이트입니다. 한글·기호를 사용하면 더 짧게 입력해주세요.");
        String email = form.getEmail().trim().toLowerCase(Locale.ROOT);
        if (accounts.findByEmail(email).isPresent() || accounts.existsByStudentNumber(form.getStudentNumber().trim()))
            throw new RuleException("이미 등록된 이메일 또는 학번입니다.");
        Account a = new Account(); a.setEmail(email); a.setStudentNumber(form.getStudentNumber().trim());
        a.setName(form.getName().trim()); a.setPassword(encoder.encode(form.getPassword())); a.setRole(Account.Role.STUDENT); accounts.save(a);
    }
    public List<Course> myCourses(Account a) {
        return a.getRole() == Account.Role.INSTRUCTOR ? courses.findByInstructorIdOrderByIdDesc(a.getId())
            : enrollments.findByStudentId(a.getId()).stream().map(Enrollment::getCourse).toList();
    }
    public Course course(Long id, Account a) {
        Course c = courses.findById(id).orElseThrow(() -> new RuleException("강좌가 없습니다."));
        boolean allowed = a.getRole() == Account.Role.INSTRUCTOR ? c.getInstructor().getId().equals(a.getId())
            : enrollments.existsByCourseIdAndStudentId(id, a.getId());
        if (!allowed) throw new AccessDeniedException("등록된 강좌에만 접근할 수 있습니다.");
        return c;
    }
    public Assignment assignment(Long id, Account a) {
        Assignment task = assignments.findById(id).orElseThrow(() -> new RuleException("과제가 없습니다."));
        course(task.getCourse().getId(), a); return task;
    }
    @Transactional public Course createCourse(Forms.CourseForm form, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Course c = new Course(); c.setTitle(form.getTitle().trim()); c.setDescription(form.getDescription()); c.setInstructor(a);
        String code; do { code = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT); } while (courses.existsByCode(code));
        c.setCode(code); return courses.save(c);
    }
    @Transactional public void enroll(String code, Account a) {
        role(a, Account.Role.STUDENT);
        Course c = courses.findByCode(code.trim().toUpperCase(Locale.ROOT)).orElseThrow(() -> new RuleException("참여코드를 확인해주세요."));
        if (enrollments.existsByCourseIdAndStudentId(c.getId(), a.getId())) throw new RuleException("이미 등록된 강좌입니다.");
        Enrollment e = new Enrollment(); e.setCourse(c); e.setStudent(a); enrollments.save(e);
    }
    @Transactional public Assignment saveAssignment(Long courseId, Long taskId, Forms.AssignmentForm form, MultipartFile attachment, Account a) {
        role(a, Account.Role.INSTRUCTOR); Course c = course(courseId, a);
        if (!form.getStartsAt().isBefore(form.getEndsAt())) throw new RuleException("종료일시는 시작일시보다 늦어야 합니다.");
        Assignment task = taskId == null ? new Assignment() : assignments.lockById(taskId).orElseThrow(() -> new RuleException("과제가 없습니다."));
        if (taskId != null && !task.getCourse().getId().equals(courseId)) throw new AccessDeniedException("과제의 강좌가 다릅니다.");
        if (taskId != null && submissions.findByAssignmentId(taskId).stream().anyMatch(s -> s.getScore() != null && s.getScore() > form.getMaxScore()))
            throw new RuleException("이미 채점된 점수보다 배점을 낮출 수 없습니다.");
        task.setCourse(c); task.setTitle(form.getTitle().trim()); task.setDescription(form.getDescription());
        task.setStartsAt(form.getStartsAt()); task.setEndsAt(form.getEndsAt()); task.setMaxScore(form.getMaxScore());
        if (attachment != null && !attachment.isEmpty()) {
            var stored = files.replace(attachment, task.getAttachmentKey()); task.setAttachmentKey(stored.key()); task.setAttachmentName(stored.name());
        }
        return assignments.save(task);
    }
    @Transactional public void deleteAssignment(Long id, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Assignment task = assignments.lockById(id).orElseThrow(() -> new RuleException("과제가 없습니다.")); course(task.getCourse().getId(), a);
        submissions.findByAssignmentId(id).forEach(s -> files.removeAfterCommit(s.getFileKey()));
        submissions.deleteByAssignmentId(id); submissions.flush(); files.removeAfterCommit(task.getAttachmentKey()); assignments.delete(task);
    }
    @Transactional public void submit(Long id, MultipartFile file, Account a) {
        role(a, Account.Role.STUDENT);
        // A shared assignment lock serializes upload, grading, edit, and deletion.
        Assignment task = assignments.lockById(id).orElseThrow(() -> new RuleException("과제가 없습니다.")); course(task.getCourse().getId(), a);
        if (!task.isOpen(now())) throw new RuleException("과제 제출 기간이 아닙니다.");
        Submission s = submissions.findByAssignmentIdAndStudentId(id, a.getId()).orElseGet(Submission::new);
        if (s.getScore() != null) throw new RuleException("채점 완료된 과제는 재제출할 수 없습니다.");
        var stored = files.replace(file, s.getFileKey());
        LocalDateTime submittedAt = now();
        if (!task.isOpen(submittedAt)) throw new RuleException("파일 저장 중 제출 기간이 종료되었습니다.");
        s.setAssignment(task); s.setStudent(a); s.setFileKey(stored.key()); s.setFileName(stored.name()); s.setSubmittedAt(submittedAt); submissions.save(s);
    }
    @Transactional public void grade(Long id, int score, String feedback, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Long taskId = submissions.assignmentId(id).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        Assignment task = assignments.lockById(taskId).orElseThrow(() -> new RuleException("과제가 없습니다."));
        course(task.getCourse().getId(), a);
        Submission s = submissions.findById(id).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        if (score < 0 || score > task.getMaxScore()) throw new RuleException("점수는 0~" + task.getMaxScore() + " 범위로 입력해주세요.");
        if (feedback == null || feedback.length() > 5000) throw new RuleException("피드백은 최대 5000자입니다.");
        s.setScore(score); s.setFeedback(feedback); s.setGradedAt(now());
    }
    public Submission submission(Long id, Account a) {
        Submission s = submissions.findById(id).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        course(s.getAssignment().getCourse().getId(), a);
        if (a.getRole() == Account.Role.STUDENT && !s.getStudent().getId().equals(a.getId())) throw new AccessDeniedException("본인의 제출물만 조회할 수 있습니다.");
        return s;
    }
    public record TaskRow(Assignment task, String status, Submission submission, boolean open) {}
    public List<TaskRow> studentTasks(Account a) {
        Map<Long, Submission> own = new HashMap<>(); submissions.findByStudentId(a.getId()).forEach(s -> own.put(s.getAssignment().getId(), s));
        return myCourses(a).stream().flatMap(c -> assignments.findByCourseIdOrderByEndsAtAsc(c.getId()).stream())
            .sorted(Comparator.comparing(Assignment::getEndsAt)).map(t -> new TaskRow(t, t.statusAt(now()), own.get(t.getId()), t.isOpen(now()))).toList();
    }
    public record StudentRow(Account student, Submission submission) {}
    public List<StudentRow> roster(Assignment task) {
        Map<Long, Submission> submitted = new HashMap<>(); submissions.findByAssignmentId(task.getId()).forEach(s -> submitted.put(s.getStudent().getId(), s));
        return enrollments.findByCourseIdOrderByStudentStudentNumberAsc(task.getCourse().getId()).stream()
            .map(e -> new StudentRow(e.getStudent(), submitted.get(e.getStudent().getId()))).toList();
    }
    public record Stats(long expected, long submitted, long graded, Double average, double rate, long urgent) {}
    public record CourseStats(Course course, Stats stats) {}
    public Stats studentStats(List<TaskRow> rows) {
        var graded = rows.stream().filter(r -> r.submission() != null && r.submission().getScore() != null && r.task().getMaxScore() > 0).toList();
        Double average = graded.isEmpty() ? null : graded.stream().mapToDouble(r -> r.submission().getScore() * 100.0 / r.task().getMaxScore()).average().orElse(0);
        long submitted = rows.stream().filter(r -> r.submission() != null).count();
        long allGraded = rows.stream().filter(r -> r.submission() != null && r.submission().getScore() != null).count();
        long urgent = rows.stream().filter(r -> r.submission() == null && r.open() && !r.task().getEndsAt().isAfter(now().plusHours(24))).count();
        return new Stats(rows.size(), submitted, allGraded, average, rows.isEmpty() ? 0 : submitted * 100.0 / rows.size(), urgent);
    }
    public Stats instructorStats(Course c) {
        var tasks = assignments.findByCourseIdOrderByEndsAtAsc(c.getId());
        long expected = (long) enrollments.findByCourseIdOrderByStudentStudentNumberAsc(c.getId()).size() * tasks.size();
        var all = tasks.stream().flatMap(t -> submissions.findByAssignmentId(t.getId()).stream()).toList();
        var graded = all.stream().filter(s -> s.getScore() != null).toList();
        Double average = graded.isEmpty() ? null : graded.stream().mapToInt(Submission::getScore).average().orElse(0);
        return new Stats(expected, all.size(), graded.size(), average, expected == 0 ? 0 : all.size() * 100.0 / expected, 0);
    }
    public Stats assignmentStats(Assignment task) {
        var rows = roster(task); var graded = rows.stream().filter(r -> r.submission() != null && r.submission().getScore() != null).toList();
        long submitted = rows.stream().filter(r -> r.submission() != null).count();
        Double avg = graded.isEmpty() ? null : graded.stream().mapToInt(r -> r.submission().getScore()).average().orElse(0);
        return new Stats(rows.size(), submitted, graded.size(), avg, rows.isEmpty() ? 0 : submitted * 100.0 / rows.size(), 0);
    }
}
