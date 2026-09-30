package com.planify;

import java.time.LocalDateTime;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

public class Forms {
    @Getter @Setter public static class Signup {
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,30}", message = "학번은 영문·숫자·하이픈으로 최대 30자입니다.") private String studentNumber;
        @NotBlank @Size(max = 80) private String name;
        @NotBlank @Email @Size(max = 150) private String email;
        @NotBlank @Size(min = 8, max = 60, message = "비밀번호는 8~60자로 입력해주세요.") private String password;
    }
    @Getter @Setter public static class CourseForm {
        @NotBlank @Size(max = 120) private String title;
        @Size(max = 4000) private String description;
    }
    @Getter @Setter public static class AssignmentForm {
        @NotBlank @Size(max = 150) private String title;
        @NotBlank @Size(max = 10000) private String description;
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") private LocalDateTime startsAt;
        @NotNull @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") private LocalDateTime endsAt;
        @Min(0) @Max(100000) private int maxScore;
    }
}
