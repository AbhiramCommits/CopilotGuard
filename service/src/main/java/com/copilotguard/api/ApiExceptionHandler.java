package com.copilotguard.api;

import com.copilotguard.diff.DiffParseException;
import com.copilotguard.github.GitHubException;
import com.copilotguard.llm.LlmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> messages.add(fe.getField() + ": " + fe.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> messages.add(ge.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), String.join("; ", messages)));
    }

    @ExceptionHandler(DiffParseException.class)
    public ResponseEntity<ApiError> handleDiffParse(DiffParseException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler({LlmException.class, GitHubException.class})
    public ResponseEntity<ApiError> handleUpstreamFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError(HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
    }

    public record ApiError(int status, String message) {
    }
}
