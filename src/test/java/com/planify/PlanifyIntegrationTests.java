package com.planify;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class PlanifyIntegrationTests {
    @Autowired PlanifyService service;
    @Autowired MockMvc mvc;
    @Autowired FileStorage files;
    @Autowired AccountRepository accounts;
    @Autowired CourseRepository courses;
    @Autowired EnrollmentRepository enrollments;
    @Autowired AssignmentRepository assignments;
    @Autowired SubmissionRepository submissions;
    @Autowired RevisionRepository revisions;
    @Autowired UnlockAuditRepository unlocks;
    @Autowired NotificationRepository notifications;
    Account instructor, student, stranger;
    Course course;
    Assignment task;
    @BeforeEach void setup() {
        notifications.deleteAll(); unlocks.deleteAll(); revisions.deleteAll();
        submissions.deleteAll(); assignments.deleteAll(); enrollments.deleteAll(); courses.deleteAll();
        accounts.findAll().stream().filter(a -> a.getRole() == Account.Role.STUDENT).forEach(accounts::delete);
        instructor = service.account("instructor@planify.local");
        student = signup("20260001", "student@test.local"); stranger = signup("20260002", "stranger@test.local");
        var form = new Forms.CourseForm(); form.setTitle("바이브 코딩"); form.setDescription("중간고사 프로젝트");
        course = service.createCourse(form, instructor); service.enroll(course.getCode().toLowerCase(), student);
        task = save(null, service.now().minusHours(1), service.now().plusHours(1), 100);
    }
    Account signup(String number, String email) {
        var f = new Forms.Signup(); f.setStudentNumber(number); f.setEmail(email); f.setName(number); f.setPassword("Password123!"); service.signup(f); return service.account(email);
    }
    Assignment save(Long id, LocalDateTime start, LocalDateTime end, int score) {
        var f = new Forms.AssignmentForm(); f.setTitle("프로젝트 제출"); f.setDescription("README와 프로젝트를 제출하세요."); f.setStartsAt(start); f.setEndsAt(end); f.setMaxScore(score);
        return service.saveAssignment(course.getId(), id, f, null, instructor);
    }
    MockMultipartFile file(String name) { return new MockMultipartFile("file", name, "application/zip", new byte[]{1,2,3}); }
    @Test void submissionResubmissionGradingAndStats() {
        service.submit(task.getId(), file("first.zip"), student);
        Submission first = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow(); String old = first.getFileKey();
        service.submit(task.getId(), file("second.zip"), student);
        Submission second = submissions.findById(first.getId()).orElseThrow();
        assertThat(second.getFileName()).isEqualTo("second.zip"); assertThat(files.load(old).exists()).isTrue();
        assertThat(service.history(second.getId(), student)).hasSize(1);
        service.grade(second.getId(), 80, "좋습니다.", instructor);
        assertThatThrownBy(() -> service.submit(task.getId(), file("third.zip"), student)).isInstanceOf(RuleException.class).hasMessageContaining("채점 완료");
        assertThat(service.studentStats(service.studentTasks(student)).average()).isEqualTo(80.0);
        assertThat(service.instructorStats(course).rate()).isEqualTo(100.0);
        assertThatThrownBy(() -> service.grade(second.getId(), 101, "", instructor)).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> save(task.getId(), task.getStartsAt(), task.getEndsAt(), 50)).isInstanceOf(RuleException.class);
    }
    @Test void periodsZeroScoreAndEmptyFile() {
        assertThatThrownBy(() -> service.submit(task.getId(), new MockMultipartFile("file", new byte[0]), student)).isInstanceOf(RuleException.class);
        save(task.getId(), service.now().minusDays(2), service.now().minusDays(1), 100);
        assertThatThrownBy(() -> service.submit(task.getId(), file("late.zip"), student)).isInstanceOf(RuleException.class);
        save(task.getId(), service.now().plusDays(1), service.now().plusDays(2), 100);
        assertThatThrownBy(() -> service.submit(task.getId(), file("early.zip"), student)).isInstanceOf(RuleException.class);
        save(task.getId(), service.now().minusMinutes(1), service.now().plusHours(1), 0);
        service.submit(task.getId(), file("zero.zip"), student);
        Submission s = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow(); service.grade(s.getId(), 0, "확인", instructor);
        assertThat(service.studentStats(service.studentTasks(student)).average()).isNull();
        assertThat(service.instructorStats(course).average()).isZero();
    }
    @Test void exactTimeBoundaries() {
        var start = LocalDateTime.of(2026,9,30,0,0); var end = start.plusHours(23).plusMinutes(59);
        task.setStartsAt(start); task.setEndsAt(end);
        assertThat(task.isOpen(start)).isTrue(); assertThat(task.isOpen(end)).isTrue();
        assertThat(task.statusAt(start.minusNanos(1))).isEqualTo("예정"); assertThat(task.statusAt(end.plusNanos(1))).isEqualTo("마감");
    }
    @Test void fileSizeAndUnauthorizedAccess() throws Exception {
        assertThatThrownBy(() -> service.submit(task.getId(), new MockMultipartFile("file", "large.zip", "application/zip", new byte[(int)FileStorage.MAX_SIZE+1]), student)).isInstanceOf(RuleException.class);
        mvc.perform(get("/student/assignments/"+task.getId()).with(user(stranger.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/files/assignments/"+task.getId()).with(user(stranger.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        service.enroll(course.getCode(), stranger); service.submit(task.getId(), file("own.zip"), student);
        Submission s = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow();
        mvc.perform(get("/files/submissions/"+s.getId()).with(user(stranger.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/instructor/courses").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/student/enroll").param("code", course.getCode()).with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/files/submissions/"+s.getId()).with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk()).andExpect(header().string("Content-Type","application/octet-stream"));
    }
    @Test void pagesRenderAndEndToEndFormActions() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()); mvc.perform(get("/signup")).andExpect(status().isOk());
        var teacher = user(instructor.getEmail()).roles("INSTRUCTOR"); var learner = user(student.getEmail()).roles("STUDENT");
        for (String path : new String[]{"/instructor/courses", "/instructor/courses/"+course.getId(), "/instructor/dashboard", "/instructor/courses/"+course.getId()+"/assignments/new", "/instructor/assignments/"+task.getId()+"/edit", "/instructor/assignments/"+task.getId()})
            mvc.perform(get(path).with(teacher)).andExpect(status().isOk());
        for (String path : new String[]{"/student/courses", "/student/dashboard", "/student/assignments", "/student/assignments/"+task.getId()})
            mvc.perform(get(path).with(learner)).andExpect(status().isOk());
        mvc.perform(multipart("/student/assignments/"+task.getId()+"/submit").file(file("project.zip")).with(learner).with(csrf())).andExpect(status().is3xxRedirection());
        Submission s = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow();
        mvc.perform(get("/instructor/assignments/"+task.getId()).with(teacher)).andExpect(status().isOk());
        mvc.perform(post("/instructor/submissions/"+s.getId()+"/grade").param("score","90").param("feedback","잘했습니다.").with(teacher).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/student/assignments/"+task.getId()).with(learner)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("잘했습니다.")));
        mvc.perform(get("/instructor/dashboard").with(teacher)).andExpect(status().isOk());
        service.deleteAssignment(task.getId(), instructor); assertThat(submissions.findById(s.getId())).isEmpty(); assertThatThrownBy(() -> files.load(s.getFileKey())).isInstanceOf(RuleException.class);
    }
    @Test void signupCannotChooseInstructorRoleAndPasswordIsHashed() throws Exception {
        mvc.perform(post("/signup").with(csrf()).param("studentNumber","20260003").param("name","학생").param("email","New@Test.Local").param("password","Password123!").param("role","INSTRUCTOR")).andExpect(status().is3xxRedirection());
        Account a = service.account("new@test.local"); assertThat(a.getRole()).isEqualTo(Account.Role.STUDENT); assertThat(a.getPassword()).startsWith("$2");
        mvc.perform(post("/login").with(csrf()).param("email","NEW@TEST.LOCAL").param("password","Password123!")).andExpect(status().is3xxRedirection()).andExpect(authenticated().withRoles("STUDENT"));
    }
    @Test void differentMaximumScoresAndNonSubmitters() {
        Assignment other = save(null, service.now().minusMinutes(1), service.now().plusHours(2), 20);
        service.enroll(course.getCode(), stranger);
        service.submit(task.getId(), file("one.zip"), student); service.submit(other.getId(), file("two.zip"), student);
        service.grade(submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow().getId(), 80, "", instructor);
        service.grade(submissions.findByAssignmentIdAndStudentId(other.getId(), student.getId()).orElseThrow().getId(), 10, "", instructor);
        assertThat(service.studentStats(service.studentTasks(student)).average()).isEqualTo(65.0);
        assertThat(service.instructorStats(course).average()).isEqualTo(45.0);
        assertThat(service.instructorStats(course).rate()).isEqualTo(50.0);
        assertThat(service.studentStats(service.studentTasks(stranger)).urgent()).isEqualTo(2);
        assertThat(service.studentStats(service.studentTasks(stranger)).average()).isNull();
        assertThat(service.roster(task).stream().filter(r -> r.submission() == null).count()).isEqualTo(1);
    }
    @Test void instructorAttachmentAndFormDefaults() throws Exception {
        var teacher = user(instructor.getEmail()).roles("INSTRUCTOR");
        mvc.perform(get("/instructor/courses/"+course.getId()+"/assignments/new").with(teacher))
            .andExpect(status().isOk()).andExpect(result -> {
                var form = (Forms.AssignmentForm) result.getModelAndView().getModel().get("form");
                assertThat(form.getMaxScore()).isZero(); assertThat(form.getStartsAt()).isEqualTo(service.now().toLocalDate().atStartOfDay());
                assertThat(form.getEndsAt()).isEqualTo(service.now().toLocalDate().atTime(23,59));
            });
        var attachment = new MockMultipartFile("attachment", "안내.pdf", "application/pdf", new byte[]{1,2,3});
        mvc.perform(multipart("/instructor/courses/"+course.getId()+"/assignments").file(attachment)
            .param("taskId", task.getId().toString()).param("title","안내 첨부 과제").param("description","설명")
            .param("startsAt",service.now().minusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
            .param("endsAt",service.now().plusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))
            .param("maxScore","100").with(teacher).with(csrf())).andExpect(status().is3xxRedirection());
        Assignment saved = assignments.findById(task.getId()).orElseThrow(); assertThat(saved.getAttachmentName()).isEqualTo("안내.pdf");
        mvc.perform(get("/files/assignments/"+task.getId()).with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk())
            .andExpect(header().exists("Content-Disposition")).andExpect(content().bytes(new byte[]{1,2,3}));
        assertThatThrownBy(() -> save(task.getId(), service.now(), service.now().minusMinutes(1), 100)).isInstanceOf(RuleException.class);
        service.deleteAssignment(task.getId(), instructor); assertThatThrownBy(() -> files.load(saved.getAttachmentKey())).isInstanceOf(RuleException.class);
    }
    @Test void sameNameAttachmentSubmissionAndResubmissionStayIndependent() throws Exception {
        var form = new Forms.AssignmentForm(); form.setTitle("동일 파일명 검증"); form.setDescription("파일명 중복 허용");
        form.setStartsAt(service.now().minusHours(1)); form.setEndsAt(service.now().plusHours(1)); form.setMaxScore(100);
        String name = "과제 자료.txt";
        byte[] teacherBytes = "교수 자료".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] studentBytes = "학생 제출".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] revisedBytes = "학생 재제출".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Assignment saved = service.saveAssignment(course.getId(), task.getId(), form,
            new MockMultipartFile("attachment", name, "text/plain", teacherBytes), instructor);
        var learner = user(student.getEmail()).roles("STUDENT");
        mvc.perform(multipart("/student/assignments/" + task.getId() + "/submit")
            .file(new MockMultipartFile("file", name, "text/plain", studentBytes)).with(learner).with(csrf())).andExpect(status().is3xxRedirection());
        Submission first = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow();
        assertThat(first.getFileKey()).isNotEqualTo(saved.getAttachmentKey()); assertThat(first.getFileName()).isEqualTo(saved.getAttachmentName());
        mvc.perform(get("/files/assignments/" + task.getId()).with(learner)).andExpect(content().bytes(teacherBytes));
        mvc.perform(get("/files/submissions/" + first.getId()).with(learner)).andExpect(content().bytes(studentBytes));
        mvc.perform(multipart("/student/assignments/" + task.getId() + "/submit")
            .file(new MockMultipartFile("file", name, "text/plain", revisedBytes)).with(learner).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/files/assignments/" + task.getId()).with(learner)).andExpect(content().bytes(teacherBytes));
        mvc.perform(get("/files/submissions/" + first.getId()).with(learner)).andExpect(content().bytes(revisedBytes));
        assertThat(files.load(first.getFileKey()).exists()).isTrue();
        assertThat(service.history(first.getId(), student)).hasSize(1);
    }
    @Test void emptySelectedFileHasSpecificMessageAndPreservesExistingSubmission() throws Exception {
        service.submit(task.getId(), file("sql.txt"), student);
        Submission previous = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow();
        mvc.perform(multipart("/student/assignments/" + task.getId() + "/submit")
            .file(new MockMultipartFile("file", "sql.txt", "text/plain", new byte[0]))
            .with(user(student.getEmail()).roles("STUDENT")).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("선택한 파일이 0바이트입니다.")));
        assertThat(submissions.findById(previous.getId()).orElseThrow().getFileKey()).isEqualTo(previous.getFileKey());
        try (var input = files.load(previous.getFileKey()).getInputStream()) { assertThat(input.readAllBytes()).containsExactly(1,2,3); }
    }
    Forms.AssignmentForm rubricForm(String text) {
        var f = new Forms.AssignmentForm(); f.setTitle("평가 항목 과제"); f.setDescription("항목별 채점");
        f.setStartsAt(service.now().minusHours(1)); f.setEndsAt(service.now().plusHours(1)); f.setMaxScore(5); f.setRubricText(text); return f;
    }
    Submission currentSubmission() { return submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow(); }
    @Test void rubricScoresAreValidatedAndSummedByServer() throws Exception {
        var f = rubricForm("기능 구현 | 60\n화면 구성 | 40");
        Assignment saved = service.saveAssignment(course.getId(), task.getId(), f, null, instructor);
        assertThat(saved.getMaxScore()).isEqualTo(100); assertThat(saved.getRubric()).hasSize(2);
        service.submit(task.getId(), file("project.zip"), student); Long id = currentSubmission().getId();
        assertThatThrownBy(() -> service.grade(id, 99, java.util.List.of(61, 20), "", instructor)).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> service.grade(id, 99, java.util.List.of(30), "", instructor)).isInstanceOf(RuleException.class);
        assertThat(currentSubmission().getScore()).isNull();
        mvc.perform(post("/instructor/submissions/"+id+"/grade").param("score","99999").param("itemScores","50","30")
            .param("feedback","항목별 평가").with(user(instructor.getEmail()).roles("INSTRUCTOR")).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(currentSubmission().getScore()).isEqualTo(80); assertThat(currentSubmission().getRubricScores()).containsEntry(0,50).containsEntry(1,30);
        f.setRubricText("다른 기준 | 100"); assertThatThrownBy(() -> service.saveAssignment(course.getId(), task.getId(), f, null, instructor)).isInstanceOf(RuleException.class);
        mvc.perform(get("/instructor/assignments/"+task.getId()).with(user(instructor.getEmail()).roles("INSTRUCTOR"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("기능 구현")));
        mvc.perform(get("/student/assignments/"+task.getId()).with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk());
        mvc.perform(get("/instructor/assignments/"+task.getId()+"/edit").with(user(instructor.getEmail()).roles("INSTRUCTOR"))).andExpect(status().isOk());
    }
    @Test void invalidRubricDoesNotModifyAssignment() {
        for (String invalid : new String[]{"형식 오류", "같은 이름 | 1\n같은 이름 | 2", "항목 | -1", "항목 | nope", "A | 100000\nB | 1"})
            assertThatThrownBy(() -> service.saveAssignment(course.getId(), task.getId(), rubricForm(invalid), null, instructor)).isInstanceOf(RuleException.class);
        assertThat(assignments.findById(task.getId()).orElseThrow().getMaxScore()).isEqualTo(100);
    }
    @Test void unlockPreservesScoreUntilResubmissionThenArchivesGradeAndDevelopment() throws Exception {
        var dev = new Forms.DevelopmentForm(); dev.setAiTools("Codex"); dev.setPrompts("첫 프롬프트"); dev.setModifications("직접 수정"); dev.setVerification("테스트 실행");
        service.submit(task.getId(), file("v1.zip"), dev, student); Long id = currentSubmission().getId(); String oldKey = currentSubmission().getFileKey();
        service.grade(id, 80, "이전 피드백", instructor);
        assertThatThrownBy(() -> service.unlock(id, "", instructor)).isInstanceOf(RuleException.class);
        service.unlock(id, "수정 기회 제공", instructor); assertThat(currentSubmission().getScore()).isEqualTo(80); assertThat(currentSubmission().isUnlocked()).isTrue();
        assertThatThrownBy(() -> service.unlock(id, "중복", instructor)).isInstanceOf(RuleException.class);
        dev.setPrompts("수정 프롬프트"); service.submit(task.getId(), file("v2.zip"), dev, student);
        Submission latest = currentSubmission(); assertThat(latest.getScore()).isNull(); assertThat(latest.getFeedback()).isNull(); assertThat(latest.isUnlocked()).isFalse();
        SubmissionRevision old = service.history(id, student).get(0); assertThat(old.getScore()).isEqualTo(80); assertThat(old.getFeedback()).isEqualTo("이전 피드백");
        assertThat(old.getDevelopment().getPrompts()).isEqualTo("첫 프롬프트"); assertThat(files.load(oldKey).exists()).isTrue();
        assertThat(service.unlockHistory(id, instructor)).hasSize(1); assertThat(service.assignmentStats(service.assignment(task.getId(), instructor)).pending()).isEqualTo(1);
        mvc.perform(get("/submissions/"+id+"/history").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("이전 피드백")));
        service.enroll(course.getCode(), stranger);
        mvc.perform(get("/files/revisions/"+old.getId()).with(user(stranger.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/submissions/"+id+"/history").with(user(stranger.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(post("/instructor/submissions/"+id+"/unlock").param("reason","bad").with(user(student.getEmail()).roles("STUDENT")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/files/revisions/"+old.getId()).with(user(instructor.getEmail()).roles("INSTRUCTOR"))).andExpect(status().isOk());
    }
    @Test void unlockedSubmissionStillCannotBeSubmittedOutsidePeriod() {
        service.submit(task.getId(), file("one.zip"), student); service.grade(currentSubmission().getId(), 70, "", instructor);
        service.unlock(currentSubmission().getId(), "허용", instructor);
        save(task.getId(), service.now().minusDays(2), service.now().minusDays(1), 100);
        assertThatThrownBy(() -> service.submit(task.getId(), file("late.zip"), student)).isInstanceOf(RuleException.class).hasMessageContaining("기간");
        assertThat(currentSubmission().getScore()).isEqualTo(70); assertThat(service.history(currentSubmission().getId(), student)).isEmpty();
        assertThatThrownBy(() -> service.unlock(currentSubmission().getId(), "허용", instructor)).isInstanceOf(RuleException.class).hasMessageContaining("기간");
    }
    @Test void duplicateHasIndependentAttachmentAndNoCopiedSubmissions() throws Exception {
        var f = rubricForm("기능 | 70\n기록 | 30");
        Assignment original = service.saveAssignment(course.getId(), task.getId(), f, file("guide.zip"), instructor);
        service.submit(task.getId(), file("student.zip"), student); service.grade(currentSubmission().getId(), null, java.util.List.of(60,20), "", instructor);
        mvc.perform(post("/instructor/assignments/"+task.getId()+"/duplicate").with(user(instructor.getEmail()).roles("INSTRUCTOR")).with(csrf())).andExpect(status().is3xxRedirection());
        Assignment copy = assignments.findByCourseIdOrderByEndsAtAsc(course.getId()).stream().filter(t -> !t.getId().equals(task.getId())).findFirst().orElseThrow();
        assertThat(copy.getTitle()).endsWith("(복사)"); assertThat(copy.getRubric()).hasSize(2); assertThat(copy.getMaxScore()).isEqualTo(100);
        assertThat(copy.getStartsAt()).isEqualTo(service.now().toLocalDate().atStartOfDay()); assertThat(copy.getEndsAt()).isEqualTo(service.now().toLocalDate().atTime(23,59));
        assertThat(copy.getAttachmentKey()).isNotEqualTo(original.getAttachmentKey()); assertThat(submissions.findByAssignmentId(copy.getId())).isEmpty();
        service.deleteAssignment(task.getId(), instructor); assertThat(files.load(copy.getAttachmentKey()).exists()).isTrue();
    }
    @Test void notificationsAreOwnedReadableAndSafeAfterAssignmentDeletion() throws Exception {
        notifications.deleteAll(); save(null, service.now().minusHours(1), service.now().plusHours(1), 100);
        assertThat(service.unread(student)).isEqualTo(1); assertThat(service.unread(stranger)).isZero();
        Notification n = service.notifications(student).get(0);
        mvc.perform(post("/notifications/"+n.getId()+"/read").with(user(stranger.getEmail()).roles("STUDENT")).with(csrf())).andExpect(status().isForbidden());
        assertThat(service.unread(student)).isEqualTo(1);
        mvc.perform(get("/notifications").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk());
        mvc.perform(post("/notifications/"+n.getId()+"/read").with(user(student.getEmail()).roles("STUDENT")).with(csrf())).andExpect(redirectedUrl("/student/assignments/"+n.getAssignmentId()));
        assertThat(service.unread(student)).isZero(); service.submit(task.getId(), file("one.zip"), student); service.grade(currentSubmission().getId(), 90, "", instructor);
        assertThat(service.notifications(student)).anyMatch(notification -> notification.getMessage().startsWith("채점 완료"));
        mvc.perform(post("/notifications/read-all").with(user(student.getEmail()).roles("STUDENT")).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(service.unread(student)).isZero();
        service.deleteAssignment(n.getAssignmentId(), instructor); assertThat(service.openNotification(n.getId(), student)).isEqualTo("/notifications");
    }
    @Test void searchAndGradingProgressUseFullRoster() throws Exception {
        save(null, service.now().plusDays(1), service.now().plusDays(2), 50);
        service.enroll(course.getCode(), stranger); service.submit(task.getId(), file("one.zip"), student);
        assertThat(service.searchStudentTasks(student,"프로젝트", course.getId(),"진행중","채점대기")).hasSize(1);
        assertThat(service.searchStudentTasks(student,"", course.getId(),"예정","미제출")).hasSize(1);
        assertThat(service.searchStudentTasks(student,"일치 없음",null,"","")).isEmpty();
        assertThat(service.searchRoster(service.assignment(task.getId(), instructor),stranger.getStudentNumber(),"미제출")).hasSize(1);
        assertThat(service.assignmentStats(service.assignment(task.getId(), instructor)).gradingRate()).isZero();
        service.grade(currentSubmission().getId(),90,"",instructor);
        assertThat(service.assignmentStats(service.assignment(task.getId(), instructor)).gradingRate()).isEqualTo(100.0);
        mvc.perform(get("/student/assignments").param("courseId",course.getId().toString()).param("status","진행중").param("submitted","채점완료").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isOk());
        mvc.perform(get("/instructor/assignments/"+task.getId()).param("submitted","미제출").with(user(instructor.getEmail()).roles("INSTRUCTOR"))).andExpect(status().isOk());
        mvc.perform(get("/student/assignments").param("status","invalid").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isBadRequest());
    }
    @Test void csvEscapesFeedbackProtectsFormulasAndRestrictsStudents() throws Exception {
        student.setName("=1+1"); accounts.save(student); service.enroll(course.getCode(), stranger);
        service.submit(task.getId(),file("one.zip"),student); service.grade(currentSubmission().getId(),75,"줄1,\"따옴표\"\n줄2",instructor);
        byte[] bytes = service.exportCsv(course.getId(),instructor); String csv = new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF"); assertThat(csv).contains("'=1+1", "줄1,\"\"따옴표\"\"\n줄2", "미제출", "채점완료");
        mvc.perform(get("/instructor/courses/"+course.getId()+"/grades.csv").with(user(instructor.getEmail()).roles("INSTRUCTOR"))).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(get("/instructor/courses/"+course.getId()+"/grades.csv").with(user(student.getEmail()).roles("STUDENT"))).andExpect(status().isForbidden());
    }
    @Test void instructorCanRotateCourseCodeAndImportRosterByStudentNumber() {
        String oldCode = course.getCode();
        String newCode = service.refreshCourseCode(course.getId(), instructor);
        assertThat(newCode).isNotEqualTo(oldCode);
        assertThat(service.course(course.getId(), student).getId()).isEqualTo(course.getId());
        assertThatThrownBy(() -> service.enroll(oldCode, stranger)).isInstanceOf(RuleException.class);

        String csv = "\uFEFF\"이름\",\"학번\",\"이메일\"\r\n\"학생\",\"20260001\",\"student@test.local\"\r\n"
            + "\"학생\",\"20260002\",\"stranger@test.local\"\r\n\"중복\",\"20260002\",\"stranger@test.local\"\r\n";
        var upload = new MockMultipartFile("file", "roster.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = service.importEnrollmentCsv(course.getId(), upload, instructor);
        assertThat(result.enrolled()).isEqualTo(1); assertThat(result.alreadyEnrolled()).isEqualTo(1);
        assertThat(enrollments.existsByCourseIdAndStudentId(course.getId(), stranger.getId())).isTrue();

        String unknownCsv = "학번\n20260002\n99999999\n";
        var invalid = new MockMultipartFile("file", "unknown.csv", "text/csv", unknownCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.importEnrollmentCsv(course.getId(), invalid, instructor))
            .isInstanceOf(RuleException.class).hasMessageContaining("등록되지 않은 학생 학번");
        assertThat(enrollments.findByCourseIdOrderByStudentStudentNumberAsc(course.getId())).hasSize(2);
    }
    @Test void developmentValidationDoesNotOverwritePriorVersion() throws Exception {
        service.submit(task.getId(),file("first.zip"),student); String oldKey = currentSubmission().getFileKey();
        mvc.perform(multipart("/student/assignments/"+task.getId()+"/submit").file(file("next.zip")).param("prompts","x".repeat(10001))
            .with(user(student.getEmail()).roles("STUDENT")).with(csrf())).andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("developmentForm","prompts"));
        assertThat(currentSubmission().getFileKey()).isEqualTo(oldKey); assertThat(service.history(currentSubmission().getId(),student)).isEmpty();
    }
    @Test void deleteCleansAllArchivedFilesAndUnlockAudits() {
        service.submit(task.getId(),file("first.zip"),student); String firstKey=currentSubmission().getFileKey();
        service.submit(task.getId(),file("second.zip"),student); String secondKey=currentSubmission().getFileKey();
        service.grade(currentSubmission().getId(),80,"",instructor); service.unlock(currentSubmission().getId(),"수정",instructor);
        Long id=currentSubmission().getId(); service.deleteAssignment(task.getId(),instructor);
        assertThat(revisions.findBySubmissionIdOrderByRevisionNumberDesc(id)).isEmpty(); assertThat(unlocks.findBySubmissionIdOrderByUnlockedAtDesc(id)).isEmpty();
        assertThatThrownBy(() -> files.load(firstKey)).isInstanceOf(RuleException.class); assertThatThrownBy(() -> files.load(secondKey)).isInstanceOf(RuleException.class);
    }
    @Test void anotherInstructorCannotCopyUnlockExportOrReadHistory() throws Exception {
        Account other = new Account(); other.setName("다른 강사"); other.setEmail("other-instructor@test.local");
        other.setPassword("test-only-unused-hash"); other.setRole(Account.Role.INSTRUCTOR); accounts.save(other);
        service.submit(task.getId(),file("one.zip"),student); service.submit(task.getId(),file("two.zip"),student);
        Long id=currentSubmission().getId(); service.grade(id,80,"",instructor);
        var strangerTeacher = user(other.getEmail()).roles("INSTRUCTOR");
        mvc.perform(post("/instructor/assignments/"+task.getId()+"/duplicate").with(strangerTeacher).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/instructor/submissions/"+id+"/unlock").param("reason","bad").with(strangerTeacher).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/instructor/courses/"+course.getId()+"/grades.csv").with(strangerTeacher)).andExpect(status().isForbidden());
        mvc.perform(get("/submissions/"+id+"/history").with(strangerTeacher)).andExpect(status().isForbidden());
        assertThat(currentSubmission().isUnlocked()).isFalse(); assertThat(service.myCourses(instructor)).hasSize(1);
    }
    @Test void historicalRubricRemainsUnchangedWhenNewVersionCriteriaChange() {
        var f = rubricForm("기능 | 70\n기록 | 30"); service.saveAssignment(course.getId(),task.getId(),f,null,instructor);
        service.submit(task.getId(),file("one.zip"),student); Long id=currentSubmission().getId();
        service.grade(id,null,java.util.List.of(60,20),"이전 채점",instructor); service.unlock(id,"재평가",instructor);
        service.submit(task.getId(),file("two.zip"),student);
        f.setRubricText("새 기준 | 50"); service.saveAssignment(course.getId(),task.getId(),f,null,instructor);
        SubmissionRevision old=service.history(id,student).get(0); assertThat(old.getMaxScore()).isEqualTo(100);
        assertThat(old.getScore()).isEqualTo(80); assertThat(old.getRubricSummary()).contains("기능: 60 / 70","기록: 20 / 30");
        assertThat(currentSubmission().getRubricScores()).isEmpty();
        assertThatThrownBy(() -> service.saveAssignment(course.getId(),task.getId(),f,new MockMultipartFile("attachment","empty.txt","text/plain",new byte[0]),instructor))
            .isInstanceOf(RuleException.class).hasMessageContaining("0바이트");
    }
}
