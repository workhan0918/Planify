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
    Account instructor, student, stranger;
    Course course;
    Assignment task;
    @BeforeEach void setup() {
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
        assertThat(second.getFileName()).isEqualTo("second.zip"); assertThatThrownBy(() -> files.load(old)).isInstanceOf(RuleException.class);
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
        assertThatThrownBy(() -> files.load(first.getFileKey())).isInstanceOf(RuleException.class);
    }
    @Test void emptySelectedFileHasSpecificMessageAndPreservesExistingSubmission() throws Exception {
        service.submit(task.getId(), file("sql.txt"), student);
        Submission previous = submissions.findByAssignmentIdAndStudentId(task.getId(), student.getId()).orElseThrow();
        mvc.perform(multipart("/student/assignments/" + task.getId() + "/submit")
            .file(new MockMultipartFile("file", "sql.txt", "text/plain", new byte[0]))
            .with(user(student.getEmail()).roles("STUDENT")).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("선택한 파일이 0바이트입니다.")));
        assertThat(submissions.findById(previous.getId()).orElseThrow().getFileKey()).isEqualTo(previous.getFileKey());
        assertThat(files.load(previous.getFileKey()).getInputStream().readAllBytes()).containsExactly(1,2,3);
    }
}
