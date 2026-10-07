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
    private final RevisionRepository revisions;
    private final UnlockAuditRepository unlocks;
    private final NotificationRepository notifications;
    public PlanifyService(AccountRepository accounts, CourseRepository courses, EnrollmentRepository enrollments,
        AssignmentRepository assignments, SubmissionRepository submissions, PasswordEncoder encoder, FileStorage files, Clock clock,
        RevisionRepository revisions, UnlockAuditRepository unlocks, NotificationRepository notifications) {
        this.accounts = accounts; this.courses = courses; this.enrollments = enrollments;
        this.assignments = assignments; this.submissions = submissions; this.encoder = encoder; this.files = files; this.clock = clock;
        this.revisions = revisions; this.unlocks = unlocks; this.notifications = notifications;
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
    @Transactional public String refreshCourseCode(Long courseId, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Course c = course(courseId, a);
        String code;
        do { code = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT); }
        while (courses.existsByCode(code));
        c.setCode(code);
        return code;
    }
    @Transactional public void enroll(String code, Account a) {
        role(a, Account.Role.STUDENT);
        Course c = courses.findByCode(code.trim().toUpperCase(Locale.ROOT)).orElseThrow(() -> new RuleException("참여코드를 확인해주세요."));
        if (enrollments.existsByCourseIdAndStudentId(c.getId(), a.getId())) throw new RuleException("이미 등록된 강좌입니다.");
        Enrollment e = new Enrollment(); e.setCourse(c); e.setStudent(a); enrollments.save(e);
    }
    public record EnrollmentImportResult(int enrolled, int alreadyEnrolled) {}
    @Transactional public EnrollmentImportResult importEnrollmentCsv(Long courseId, MultipartFile upload, Account a) {
        role(a, Account.Role.INSTRUCTOR); Course target = course(courseId, a);
        if (upload == null || upload.isEmpty()) throw new RuleException("수강생 CSV 파일을 선택해주세요.");
        if (upload.getSize() > 5L * 1024 * 1024) throw new RuleException("CSV 파일은 최대 5MB까지 허용됩니다.");
        String csv;
        try { csv = new String(upload.getBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", ""); }
        catch (java.io.IOException ex) { throw new RuleException("CSV 파일을 읽을 수 없습니다."); }
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) throw new RuleException("CSV에 헤더와 학번을 입력해주세요.");
        int numberColumn = -1;
        for (int i = 0; i < rows.get(0).size(); i++) {
            String header = rows.get(0).get(i).trim();
            if (header.equals("학번") || header.equalsIgnoreCase("studentNumber")) { numberColumn = i; break; }
        }
        if (numberColumn < 0) throw new RuleException("CSV 첫 행에 '학번' 열이 필요합니다. 강좌 성적표 CSV도 사용할 수 있습니다.");
        LinkedHashMap<String, Account> studentsByNumber = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String number = numberColumn < row.size() ? row.get(numberColumn).trim() : "";
            if (number.isEmpty() || !seen.add(number)) continue;
            if (seen.size() > 1000) throw new RuleException("한 번에 최대 1,000명까지 등록할 수 있습니다.");
            Account student = accounts.findByStudentNumber(number).filter(x -> x.getRole() == Account.Role.STUDENT).orElse(null);
            if (student == null) { if (unknown.size() < 10) unknown.add(number + " (" + (i + 1) + "행)"); }
            else studentsByNumber.put(number, student);
        }
        if (!unknown.isEmpty()) throw new RuleException("등록되지 않은 학생 학번이 있습니다. 먼저 학생 계정을 생성해주세요: " + String.join(", ", unknown));
        int added = 0, existing = 0;
        for (Account student : studentsByNumber.values()) {
            if (enrollments.existsByCourseIdAndStudentId(target.getId(), student.getId())) { existing++; continue; }
            Enrollment enrollment = new Enrollment(); enrollment.setCourse(target); enrollment.setStudent(student); enrollments.save(enrollment); added++;
        }
        return new EnrollmentImportResult(added, existing);
    }
    private List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"' && cell.length() == 0) quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (quoted) throw new RuleException("CSV 따옴표 형식을 확인해주세요.");
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
    @Transactional public Assignment saveAssignment(Long courseId, Long taskId, Forms.AssignmentForm form, MultipartFile attachment, Account a) {
        role(a, Account.Role.INSTRUCTOR); Course c = course(courseId, a);
        if (!form.getStartsAt().isBefore(form.getEndsAt())) throw new RuleException("종료일시는 시작일시보다 늦어야 합니다.");
        Assignment task = taskId == null ? new Assignment() : assignments.lockById(taskId).orElseThrow(() -> new RuleException("과제가 없습니다."));
        if (taskId != null && !task.getCourse().getId().equals(courseId)) throw new AccessDeniedException("과제의 강좌가 다릅니다.");
        List<RubricCriterion> rubric = parseRubric(form.getRubricText());
        int maxScore = rubric.isEmpty() ? form.getMaxScore() : rubric.stream().mapToInt(RubricCriterion::getPoints).sum();
        if (maxScore < 0 || maxScore > 100000) throw new RuleException("배점 합계는 0~100,000점이어야 합니다.");
        if (taskId != null && submissions.findByAssignmentId(taskId).stream().anyMatch(s -> s.getScore() != null && s.getScore() > maxScore))
            throw new RuleException("이미 채점된 점수보다 배점을 낮출 수 없습니다.");
        if (taskId != null && !rubricText(task).equals(formatRubric(rubric)) && submissions.findByAssignmentId(taskId).stream().anyMatch(s -> s.getScore() != null))
            throw new RuleException("채점된 제출물이 있는 과제는 평가 항목을 변경할 수 없습니다.");
        task.setCourse(c); task.setTitle(form.getTitle().trim()); task.setDescription(form.getDescription());
        task.setStartsAt(form.getStartsAt()); task.setEndsAt(form.getEndsAt()); task.setMaxScore(maxScore);
        task.getRubric().clear(); task.getRubric().addAll(rubric);
        if (attachment != null && attachment.getOriginalFilename() != null && !attachment.getOriginalFilename().isBlank()) {
            var stored = files.replace(attachment, task.getAttachmentKey()); task.setAttachmentKey(stored.key()); task.setAttachmentName(stored.name());
        }
        Assignment saved = assignments.save(task);
        if (taskId == null) enrollments.findByCourseIdOrderByStudentStudentNumberAsc(courseId)
            .forEach(e -> notify(e.getStudent(), saved, "새 과제: " + saved.getTitle()));
        return saved;
    }
    @Transactional public void deleteAssignment(Long id, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Assignment task = assignments.lockById(id).orElseThrow(() -> new RuleException("과제가 없습니다.")); course(task.getCourse().getId(), a);
        submissions.findByAssignmentId(id).forEach(s -> {
            revisions.findBySubmissionIdOrderByRevisionNumberDesc(s.getId()).forEach(r -> files.removeAfterCommit(r.getFileKey()));
            revisions.deleteBySubmissionId(s.getId()); unlocks.deleteBySubmissionId(s.getId()); files.removeAfterCommit(s.getFileKey());
        });
        revisions.flush(); unlocks.flush();
        submissions.deleteByAssignmentId(id); submissions.flush(); files.removeAfterCommit(task.getAttachmentKey()); assignments.delete(task);
    }
    @Transactional public void submit(Long id, MultipartFile file, Account a) {
        submit(id, file, new Forms.DevelopmentForm(), a);
    }
    @Transactional public void submit(Long id, MultipartFile file, Forms.DevelopmentForm development, Account a) {
        role(a, Account.Role.STUDENT);
        // A shared assignment lock serializes upload, grading, edit, and deletion.
        Assignment task = assignments.lockById(id).orElseThrow(() -> new RuleException("과제가 없습니다.")); course(task.getCourse().getId(), a);
        if (!task.isOpen(now())) throw new RuleException("과제 제출 기간이 아닙니다.");
        Submission s = submissions.findByAssignmentIdAndStudentId(id, a.getId()).orElseGet(Submission::new);
        if (s.getScore() != null && !s.isUnlocked()) throw new RuleException("채점 완료된 과제는 재제출할 수 없습니다.");
        validateDevelopment(development);
        var stored = files.replace(file, null);
        LocalDateTime submittedAt = now();
        if (!task.isOpen(submittedAt)) throw new RuleException("파일 저장 중 제출 기간이 종료되었습니다.");
        if (s.getId() != null) archive(s);
        s.setAssignment(task); s.setStudent(a); s.setFileKey(stored.key()); s.setFileName(stored.name()); s.setSubmittedAt(submittedAt);
        s.setDevelopment(development.record()); s.setScore(null); s.setFeedback(null); s.setGradedAt(null); s.getRubricScores().clear(); s.setResubmissionAllowed(false);
        submissions.save(s);
    }
    @Transactional public void grade(Long id, int score, String feedback, Account a) {
        grade(id, score, null, feedback, a);
    }
    @Transactional public void grade(Long id, Integer score, List<Integer> itemScores, String feedback, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Long taskId = submissions.assignmentId(id).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        Assignment task = assignments.lockById(taskId).orElseThrow(() -> new RuleException("과제가 없습니다."));
        course(task.getCourse().getId(), a);
        Submission s = submissions.findById(id).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        if (!task.getRubric().isEmpty()) {
            if (itemScores == null || itemScores.size() != task.getRubric().size()) throw new RuleException("모든 평가 항목의 점수를 입력해주세요.");
            for (int i = 0; i < itemScores.size(); i++) {
                Integer points = itemScores.get(i);
                if (points == null || points < 0 || points > task.getRubric().get(i).getPoints()) throw new RuleException("평가 항목별 배점 범위를 확인해주세요.");
            }
            score = itemScores.stream().mapToInt(Integer::intValue).sum();
        }
        if (score == null || score < 0 || score > task.getMaxScore()) throw new RuleException("점수는 0~" + task.getMaxScore() + " 범위로 입력해주세요.");
        if (feedback == null || feedback.length() > 5000) throw new RuleException("피드백은 최대 5000자입니다.");
        s.getRubricScores().clear();
        if (!task.getRubric().isEmpty()) for (int i = 0; i < itemScores.size(); i++) s.getRubricScores().put(i, itemScores.get(i));
        s.setScore(score); s.setFeedback(feedback); s.setGradedAt(now()); s.setResubmissionAllowed(false);
        notify(s.getStudent(), task, "채점 완료: " + task.getTitle());
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
    public record Stats(long expected, long submitted, long graded, Double average, double rate, long urgent) {
        public long pending() { return submitted - graded; }
        public double gradingRate() { return submitted == 0 ? 0 : graded * 100.0 / submitted; }
    }
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
    public String rubricText(Assignment task) { return formatRubric(task.getRubric()); }
    private String formatRubric(List<RubricCriterion> rubric) {
        return rubric.stream().map(r -> r.getName() + " | " + r.getPoints()).collect(java.util.stream.Collectors.joining("\n"));
    }
    private List<RubricCriterion> parseRubric(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        if (text.length() > 4000) throw new RuleException("평가 항목은 최대 4,000자입니다.");
        List<RubricCriterion> result = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            int separator = line.lastIndexOf('|');
            if (separator < 1) throw new RuleException("평가 항목은 '항목명 | 배점' 형식으로 한 줄씩 입력해주세요.");
            String name = line.substring(0, separator).trim(); int points;
            try { points = Integer.parseInt(line.substring(separator + 1).trim()); }
            catch (NumberFormatException ex) { throw new RuleException("평가 항목 배점은 정수여야 합니다."); }
            if (name.isBlank() || name.length() > 100 || name.contains("|") || !names.add(name) || points < 0 || points > 100000)
                throw new RuleException("항목명은 서로 다른 1~100자, 배점은 0~100,000점으로 설정해주세요.");
            RubricCriterion criterion = new RubricCriterion(); criterion.setName(name); criterion.setPoints(points); result.add(criterion);
        }
        if (result.size() > 20) throw new RuleException("평가 항목은 최대 20개입니다.");
        return result;
    }
    private void validateDevelopment(Forms.DevelopmentForm d) {
        if (d == null || tooLong(d.getAiTools(), 500) || tooLong(d.getPrompts(), 10000) || tooLong(d.getModifications(), 10000) || tooLong(d.getVerification(), 10000))
            throw new RuleException("개발 기록은 AI 도구 500자, 나머지 항목은 각각 10,000자 이내로 입력해주세요.");
    }
    private boolean tooLong(String value, int max) { return value != null && value.length() > max; }
    private void archive(Submission s) {
        SubmissionRevision r = new SubmissionRevision(); r.setSubmission(s); r.setRevisionNumber((int) revisions.countBySubmissionId(s.getId()) + 1);
        r.setFileKey(s.getFileKey()); r.setFileName(s.getFileName()); r.setSubmittedAt(s.getSubmittedAt());
        r.setScore(s.getScore()); r.setFeedback(s.getFeedback()); r.setGradedAt(s.getGradedAt()); r.setMaxScore(s.getAssignment().getMaxScore());
        r.setDevelopment(s.getDevelopment() == null ? null : s.getDevelopment().copy());
        List<String> details = new ArrayList<>();
        for (int i = 0; i < s.getAssignment().getRubric().size(); i++) {
            RubricCriterion criterion = s.getAssignment().getRubric().get(i);
            details.add(criterion.getName() + ": " + (s.getRubricScores().containsKey(i) ? s.getRubricScores().get(i) : "미채점") + " / " + criterion.getPoints());
        }
        r.setRubricSummary(String.join("\n", details)); revisions.save(r);
    }
    public List<SubmissionRevision> history(Long submissionId, Account a) { submission(submissionId, a); return revisions.findBySubmissionIdOrderByRevisionNumberDesc(submissionId); }
    public List<UnlockAudit> unlockHistory(Long submissionId, Account a) { submission(submissionId, a); return unlocks.findBySubmissionIdOrderByUnlockedAtDesc(submissionId); }
    public SubmissionRevision revision(Long revisionId, Account a) {
        SubmissionRevision r = revisions.findById(revisionId).orElseThrow(() -> new RuleException("제출 이력이 없습니다.")); submission(r.getSubmission().getId(), a); return r;
    }
    @Transactional public void unlock(Long submissionId, String reason, Account a) {
        role(a, Account.Role.INSTRUCTOR);
        Long taskId = submissions.assignmentId(submissionId).orElseThrow(() -> new RuleException("제출물이 없습니다."));
        Assignment task = assignments.lockById(taskId).orElseThrow(() -> new RuleException("과제가 없습니다.")); course(task.getCourse().getId(), a);
        Submission s = submissions.findById(submissionId).orElseThrow();
        if (!task.isOpen(now())) throw new RuleException("제출 기간 안에서만 잠금을 해제할 수 있습니다.");
        if (s.getScore() == null || s.isUnlocked()) throw new RuleException("채점 완료되어 잠긴 제출물만 해제할 수 있습니다.");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new RuleException("해제 사유를 1~500자로 입력해주세요.");
        s.setResubmissionAllowed(true); UnlockAudit audit = new UnlockAudit(); audit.setSubmission(s); audit.setInstructor(a); audit.setReason(reason.trim()); audit.setUnlockedAt(now()); unlocks.save(audit);
        notify(s.getStudent(), task, "재제출 허용: " + task.getTitle());
    }
    @Transactional public Assignment duplicate(Long id, Account a) {
        role(a, Account.Role.INSTRUCTOR); Assignment original = assignments.lockById(id).orElseThrow(() -> new RuleException("과제가 없습니다."));
        course(original.getCourse().getId(), a);
        Assignment copy = new Assignment(); copy.setCourse(original.getCourse());
        String title = original.getTitle(); copy.setTitle(title.substring(0, Math.min(title.length(), 145)) + " (복사)");
        copy.setDescription(original.getDescription()); copy.setMaxScore(original.getMaxScore());
        copy.setStartsAt(now().toLocalDate().atStartOfDay()); copy.setEndsAt(now().toLocalDate().atTime(23,59));
        original.getRubric().forEach(r -> copy.getRubric().add(r.copy()));
        if (original.getAttachmentKey() != null) { var f = files.copy(original.getAttachmentKey(), original.getAttachmentName()); copy.setAttachmentKey(f.key()); copy.setAttachmentName(f.name()); }
        assignments.save(copy);
        enrollments.findByCourseIdOrderByStudentStudentNumberAsc(copy.getCourse().getId()).forEach(e -> notify(e.getStudent(), copy, "새 과제: " + copy.getTitle())); return copy;
    }
    private void notify(Account recipient, Assignment task, String message) {
        Notification n = new Notification(); n.setRecipient(recipient); n.setAssignmentId(task.getId()); n.setMessage(message); n.setCreatedAt(now()); notifications.save(n);
    }
    public long unread(Account a) { return notifications.countByRecipientIdAndReadAtIsNull(a.getId()); }
    public List<Notification> notifications(Account a) { return notifications.findByRecipientIdOrderByCreatedAtDescIdDesc(a.getId()); }
    @Transactional public String openNotification(Long id, Account a) {
        Notification n = notifications.findById(id).orElseThrow(() -> new RuleException("알림이 없습니다."));
        if (!n.getRecipient().getId().equals(a.getId())) throw new AccessDeniedException("본인의 알림만 확인할 수 있습니다.");
        n.setReadAt(now());
        if (n.getAssignmentId() == null || assignments.findById(n.getAssignmentId()).isEmpty()) return "/notifications";
        assignment(n.getAssignmentId(), a); return (a.getRole() == Account.Role.STUDENT ? "/student/assignments/" : "/instructor/assignments/") + n.getAssignmentId();
    }
    @Transactional public void readAll(Account a) { notifications(a).stream().filter(n -> n.getReadAt() == null).forEach(n -> n.setReadAt(now())); }
    private boolean matches(String value, String q) { return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)); }
    private String submissionStatus(Submission s) { return s == null ? "미제출" : s.getScore() == null ? "채점대기" : "채점완료"; }
    public List<TaskRow> searchStudentTasks(Account a, String q, Long courseId, String status, String submitted) {
        validateFilters(status, submitted);
        return studentTasks(a).stream().filter(r -> courseId == null || r.task().getCourse().getId().equals(courseId))
            .filter(r -> q.isBlank() || matches(r.task().getTitle(), q) || matches(r.task().getCourse().getTitle(), q))
            .filter(r -> status.isBlank() || r.status().equals(status))
            .filter(r -> submitted.isBlank() || submissionStatus(r.submission()).equals(submitted)).toList();
    }
    public List<Assignment> searchInstructorTasks(Long courseId, Account a, String q, String status) {
        validateFilters(status, "");
        return tasks(courseId, a).stream().filter(t -> q.isBlank() || matches(t.getTitle(), q)).filter(t -> status.isBlank() || t.statusAt(now()).equals(status)).toList();
    }
    public List<StudentRow> searchRoster(Assignment task, String q, String status) {
        validateFilters("", status);
        return roster(task).stream().filter(r -> q.isBlank() || matches(r.student().getName(), q) || matches(r.student().getStudentNumber(), q))
            .filter(r -> status.isBlank() || submissionStatus(r.submission()).equals(status)).toList();
    }
    private void validateFilters(String status, String submission) {
        if (!Set.of("", "예정", "진행중", "마감").contains(status) || !Set.of("", "미제출", "채점대기", "채점완료").contains(submission)) throw new RuleException("검색 조건을 확인해주세요.");
    }
    public byte[] exportCsv(Long courseId, Account a) {
        role(a, Account.Role.INSTRUCTOR); course(courseId, a);
        List<Assignment> tasks = assignments.findByCourseIdOrderByEndsAtAsc(courseId);
        List<String> headers = new ArrayList<>(List.of("학번", "이름", "이메일"));
        for (Assignment task : tasks) { String prefix = task.getTitle() + " (#" + task.getId() + ") "; headers.addAll(List.of(prefix + "점수", prefix + "배점", prefix + "피드백", prefix + "상태")); }
        StringBuilder csv = new StringBuilder("\uFEFF").append(csvRow(headers));
        Map<Long, Map<Long, Submission>> byTask = new HashMap<>();
        for (Assignment task : tasks) { Map<Long, Submission> map = new HashMap<>(); submissions.findByAssignmentId(task.getId()).forEach(s -> map.put(s.getStudent().getId(), s)); byTask.put(task.getId(), map); }
        for (Enrollment enrollment : enrollments.findByCourseIdOrderByStudentStudentNumberAsc(courseId)) {
            Account student = enrollment.getStudent(); List<String> row = new ArrayList<>(List.of(student.getStudentNumber(), student.getName(), student.getEmail()));
            for (Assignment task : tasks) { Submission s = byTask.get(task.getId()).get(student.getId()); row.add(s == null || s.getScore() == null ? "" : s.getScore().toString()); row.add(Integer.toString(task.getMaxScore())); row.add(s == null || s.getFeedback() == null ? "" : s.getFeedback()); row.add(submissionStatus(s)); }
            csv.append(csvRow(row));
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private String csvRow(List<String> cells) {
        return cells.stream().map(cell -> { String value = cell == null ? "" : cell; String first = value.stripLeading();
            if (!first.isEmpty() && "=+-@".indexOf(first.charAt(0)) >= 0) value = "'" + value;
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }).collect(java.util.stream.Collectors.joining(",")) + "\r\n";
    }
}
