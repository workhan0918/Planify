package com.planify;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WebController {
    private final PlanifyService service;
    private final FileStorage files;
    public WebController(PlanifyService service, FileStorage files) { this.service = service; this.files = files; }
    @ModelAttribute("me") public Account me(Principal principal) { return principal == null ? null : service.account(principal.getName()); }
    @ModelAttribute("unreadCount") public long unreadCount(Principal principal) { return principal == null ? 0 : service.unread(user(principal)); }
    private Account user(Principal p) { return service.account(p.getName()); }
    private String done(RedirectAttributes flash, String message, String location) { flash.addFlashAttribute("success", message); return "redirect:" + location; }
    @GetMapping("/") public String home(Principal p) { return user(p).getRole() == Account.Role.INSTRUCTOR ? "redirect:/instructor/dashboard" : "redirect:/student/dashboard"; }
    @GetMapping("/login") public String login() { return "login"; }
    @GetMapping("/signup") public String signup(Model m) { m.addAttribute("form", new Forms.Signup()); return "signup"; }
    @PostMapping("/signup") public String signup(@Valid @ModelAttribute("form") Forms.Signup form, BindingResult result, RedirectAttributes flash) {
        if (result.hasErrors()) return "signup";
        try { service.signup(form); } catch (RuleException ex) { result.reject("duplicate", ex.getMessage()); return "signup"; }
        return done(flash, "회원가입이 완료되었습니다. 로그인해주세요.", "/login");
    }
    @GetMapping("/instructor/courses") public String courses(Principal p, Model m) {
        m.addAttribute("courses", service.myCourses(user(p))); m.addAttribute("form", new Forms.CourseForm()); return "instructor/courses";
    }
    @PostMapping("/instructor/courses") public String createCourse(@Valid @ModelAttribute("form") Forms.CourseForm form, BindingResult result, Principal p, Model m, RedirectAttributes flash) {
        if (result.hasErrors()) { m.addAttribute("courses", service.myCourses(user(p))); return "instructor/courses"; }
        Course c = service.createCourse(form, user(p)); return done(flash, "강좌가 개설되었습니다. 참여코드를 학생에게 안내해주세요.", "/instructor/courses/" + c.getId());
    }
    @PostMapping("/instructor/courses/{id}/refresh-code") public String refreshCourseCode(@PathVariable Long id, Principal p, RedirectAttributes flash) {
        service.refreshCourseCode(id, user(p));
        return done(flash, "새 참여코드가 발급되었습니다. 이전 코드는 더 이상 사용할 수 없습니다.", "/instructor/courses/" + id);
    }
    @PostMapping("/instructor/courses/{id}/enrollments/import") public String importEnrollments(@PathVariable Long id,
        @RequestParam("file") MultipartFile file, Principal p, RedirectAttributes flash) {
        PlanifyService.EnrollmentImportResult result = service.importEnrollmentCsv(id, file, user(p));
        String message = "수강 등록 " + result.enrolled() + "명";
        if (result.alreadyEnrolled() > 0) message += ", 기존 등록 " + result.alreadyEnrolled() + "명 건너뜀";
        return done(flash, message + " 처리되었습니다.", "/instructor/courses/" + id);
    }
    @GetMapping("/instructor/courses/{id}") public String course(@PathVariable Long id, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String status, Principal p, Model m) {
        Course c = service.course(id, user(p)); m.addAttribute("course", c);
        m.addAttribute("students", service.students(id, user(p)));
        m.addAttribute("tasks", service.searchInstructorTasks(id, user(p), q.trim(), status));
        m.addAttribute("q", q); m.addAttribute("status", status); return "instructor/course";
    }
    @GetMapping("/instructor/courses/{id}/assignments/new") public String newAssignment(@PathVariable Long id, Principal p, Model m) {
        var form = new Forms.AssignmentForm(); form.setStartsAt(service.now().toLocalDate().atStartOfDay()); form.setEndsAt(service.now().toLocalDate().atTime(23, 59));
        m.addAttribute("form", form); m.addAttribute("course", service.course(id, user(p))); m.addAttribute("taskId", null); return "instructor/assignment-form";
    }
    @GetMapping("/instructor/assignments/{id}/edit") public String editAssignment(@PathVariable Long id, Principal p, Model m) {
        Assignment a = service.assignment(id, user(p)); var form = new Forms.AssignmentForm();
        form.setTitle(a.getTitle()); form.setDescription(a.getDescription()); form.setStartsAt(a.getStartsAt()); form.setEndsAt(a.getEndsAt()); form.setMaxScore(a.getMaxScore());
        form.setRubricText(service.rubricText(a));
        m.addAttribute("form", form); m.addAttribute("course", a.getCourse()); m.addAttribute("taskId", id); m.addAttribute("attachmentName", a.getAttachmentName()); return "instructor/assignment-form";
    }
    @PostMapping("/instructor/courses/{courseId}/assignments") public String saveAssignment(@PathVariable Long courseId,
        @RequestParam(required = false) Long taskId, @Valid @ModelAttribute("form") Forms.AssignmentForm form, BindingResult result,
        @RequestParam(required = false) MultipartFile attachment, Principal p, Model m, RedirectAttributes flash) {
        Course c = service.course(courseId, user(p)); if (taskId != null) service.assignment(taskId, user(p));
        if (!result.hasErrors()) {
            try { service.saveAssignment(courseId, taskId, form, attachment, user(p)); }
            catch (RuleException ex) { result.reject("rule", ex.getMessage()); }
        }
        if (result.hasErrors()) { m.addAttribute("course", c); m.addAttribute("taskId", taskId); return "instructor/assignment-form"; }
        return done(flash, "과제가 저장되었습니다.", "/instructor/courses/" + courseId);
    }
    @PostMapping("/instructor/assignments/{id}/delete") public String delete(@PathVariable Long id, Principal p, RedirectAttributes flash) {
        Long courseId = service.assignment(id, user(p)).getCourse().getId(); service.deleteAssignment(id, user(p));
        return done(flash, "과제와 관련 제출물이 삭제되었습니다.", "/instructor/courses/" + courseId);
    }
    @GetMapping("/instructor/assignments/{id}") public String submissions(@PathVariable Long id, @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "") String submitted, Principal p, Model m) {
        Assignment task = service.assignment(id, user(p)); m.addAttribute("task", task); m.addAttribute("rows", service.searchRoster(task, q.trim(), submitted));
        m.addAttribute("q", q); m.addAttribute("submitted", submitted); m.addAttribute("open", task.isOpen(service.now()));
        m.addAttribute("stats", service.assignmentStats(task)); return "instructor/submissions";
    }
    @PostMapping("/instructor/submissions/{id}/grade") public String grade(@PathVariable Long id, @RequestParam(required = false) Integer score,
        @RequestParam(required = false) java.util.List<Integer> itemScores,
        @RequestParam(defaultValue = "") String feedback, Principal p, RedirectAttributes flash) {
        Long taskId = service.submission(id, user(p)).getAssignment().getId(); service.grade(id, score, itemScores, feedback, user(p));
        return done(flash, "점수와 피드백이 저장되었습니다.", "/instructor/assignments/" + taskId);
    }
    @GetMapping("/instructor/dashboard") public String instructorDashboard(Principal p, Model m) {
        var courses = service.myCourses(user(p));
        m.addAttribute("courseStats", courses.stream().map(c -> new PlanifyService.CourseStats(c, service.instructorStats(c))).toList());
        m.addAttribute("labels", courses.stream().map(Course::getTitle).toList());
        m.addAttribute("rates", courses.stream().map(c -> service.instructorStats(c).rate()).toList());
        m.addAttribute("averages", courses.stream().map(c -> service.instructorStats(c).average()).toList()); return "instructor/dashboard";
    }
    @GetMapping("/student/courses") public String studentCourses(Principal p, Model m) { m.addAttribute("courses", service.myCourses(user(p))); return "student/courses"; }
    @PostMapping("/student/enroll") public String enroll(@RequestParam String code, Principal p, RedirectAttributes flash) {
        service.enroll(code, user(p)); return done(flash, "수강 등록되었습니다.", "/student/courses");
    }
    @GetMapping("/student/assignments") public String studentTasks(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Long courseId,
        @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String submitted, Principal p, Model m) {
        m.addAttribute("rows", service.searchStudentTasks(user(p), q.trim(), courseId, status, submitted)); m.addAttribute("courses", service.myCourses(user(p)));
        m.addAttribute("q", q); m.addAttribute("courseId", courseId); m.addAttribute("status", status); m.addAttribute("submitted", submitted); return "student/assignments";
    }
    @GetMapping("/student/assignments/{id}") public String studentTask(@PathVariable Long id, Principal p, Model m) {
        Submission s = service.ownSubmission(id, user(p)); Forms.DevelopmentForm form = new Forms.DevelopmentForm();
        if (s != null && s.getDevelopment() != null) { DevelopmentRecord d = s.getDevelopment(); form.setAiTools(d.getAiTools()); form.setPrompts(d.getPrompts()); form.setModifications(d.getModifications()); form.setVerification(d.getVerification()); }
        m.addAttribute("developmentForm", form); return renderStudentTask(id, p, m);
    }
    private String renderStudentTask(Long id, Principal p, Model m) {
        Assignment task = service.assignment(id, user(p)); m.addAttribute("task", task); m.addAttribute("submission", service.ownSubmission(id, user(p)));
        m.addAttribute("status", task.statusAt(service.now())); m.addAttribute("open", task.isOpen(service.now())); return "student/assignment";
    }
    @PostMapping("/student/assignments/{id}/submit") public String submit(@PathVariable Long id, @RequestParam MultipartFile file,
        @Valid @ModelAttribute("developmentForm") Forms.DevelopmentForm development, BindingResult result, Principal p, Model m, RedirectAttributes flash) {
        if (result.hasErrors()) return renderStudentTask(id, p, m);
        service.submit(id, file, development, user(p)); return done(flash, "제출이 완료되었습니다.", "/student/assignments/" + id);
    }
    @GetMapping("/student/dashboard") public String studentDashboard(Principal p, Model m) {
        var rows = service.studentTasks(user(p)); m.addAttribute("stats", service.studentStats(rows));
        m.addAttribute("urgentRows", rows.stream().filter(r -> r.open() && r.submission() == null && !r.task().getEndsAt().isAfter(service.now().plusHours(24))).toList());
        return "student/dashboard";
    }
    @GetMapping("/files/assignments/{id}") public ResponseEntity<Resource> attachment(@PathVariable Long id, Principal p) {
        Assignment a = service.assignment(id, user(p)); return download(a.getAttachmentKey(), a.getAttachmentName());
    }
    @GetMapping("/files/submissions/{id}") public ResponseEntity<Resource> submissionFile(@PathVariable Long id, Principal p) {
        Submission s = service.submission(id, user(p)); return download(s.getFileKey(), s.getFileName());
    }
    @PostMapping("/instructor/assignments/{id}/duplicate") public String duplicate(@PathVariable Long id, Principal p, RedirectAttributes flash) {
        Assignment copy = service.duplicate(id, user(p)); return done(flash, "과제가 복제되었습니다. 당일로 설정된 제출 기간을 확인해주세요.", "/instructor/assignments/" + copy.getId() + "/edit");
    }
    @PostMapping("/instructor/submissions/{id}/unlock") public String unlock(@PathVariable Long id, @RequestParam String reason, Principal p, RedirectAttributes flash) {
        Long taskId = service.submission(id, user(p)).getAssignment().getId(); service.unlock(id, reason, user(p));
        return done(flash, "재제출 잠금이 해제되었습니다. 실제 재제출 시 이전 점수는 이력으로 이동합니다.", "/instructor/assignments/" + taskId);
    }
    @GetMapping("/submissions/{id}/history") public String history(@PathVariable Long id, Principal p, Model m) {
        m.addAttribute("submission", service.submission(id, user(p))); m.addAttribute("revisions", service.history(id, user(p)));
        m.addAttribute("unlocks", service.unlockHistory(id, user(p))); return "history";
    }
    @GetMapping("/files/revisions/{id}") public ResponseEntity<Resource> revisionFile(@PathVariable Long id, Principal p) {
        SubmissionRevision revision = service.revision(id, user(p)); return download(revision.getFileKey(), revision.getFileName());
    }
    @GetMapping("/instructor/courses/{id}/grades.csv") public ResponseEntity<byte[]> csv(@PathVariable Long id, Principal p) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"planify-course-" + id + "-grades.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(service.exportCsv(id, user(p)));
    }
    @GetMapping("/notifications") public String notifications(Principal p, Model m) { m.addAttribute("notifications", service.notifications(user(p))); return "notifications"; }
    @PostMapping("/notifications/{id}/read") public String readNotification(@PathVariable Long id, Principal p) { return "redirect:" + service.openNotification(id, user(p)); }
    @PostMapping("/notifications/read-all") public String readAll(Principal p, RedirectAttributes flash) { service.readAll(user(p)); return done(flash, "알림을 모두 읽음으로 표시했습니다.", "/notifications"); }
    private ResponseEntity<Resource> download(String key, String name) {
        Resource resource = files.load(key);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "no-store").body(resource);
    }
}
