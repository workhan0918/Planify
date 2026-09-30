package com.planify;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@ControllerAdvice
public class WebErrors {
    @ExceptionHandler(RuleException.class) public String rule(RuleException ex, Model m, HttpServletResponse response) { return error(ex.getMessage(), 400, m, response); }
    @ExceptionHandler(AccessDeniedException.class) public String denied(Model m, HttpServletResponse response) { return error("접근 권한이 없습니다. 본인의 강좌와 제출물만 확인할 수 있습니다.", 403, m, response); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) public String size(Model m, HttpServletResponse response) { return error("파일은 1개, 최대 20MB까지 업로드할 수 있습니다.", 413, m, response); }
    @ExceptionHandler(DataIntegrityViolationException.class) public String duplicate(Model m, HttpServletResponse response) { return error("이미 등록된 데이터입니다. 입력 내용을 확인해주세요.", 409, m, response); }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public String invalid(Model m, HttpServletResponse response) { return error("입력값을 확인해주세요.", 400, m, response); }
    private String error(String message, int status, Model m, HttpServletResponse response) {
        response.setStatus(status); m.addAttribute("message", message); return "error";
    }
}
