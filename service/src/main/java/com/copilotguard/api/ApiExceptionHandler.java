package com.copilotguard.api;

import com.copilotguard.audit.BlockedSecretException;
import com.copilotguard.diff.DiffParseException;
import com.copilotguard.github.GitHubException;
import com.copilotguard.llm.LlmException;
import com.copilotguard.validation.ValidationException;
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

    @ExceptionHandler(BlockedSecretException.class)
    public ResponseEntity<ApiError> handleBlockedSecret(BlockedSecretException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(HttpStatus.UNPROCESSABLE_ENTITY.value(), ex.getMessage()));
    }

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<ApiError> handleRunNotFound(RunNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(CommentNotFoundException.class)
    public ResponseEntity<ApiError> handleCommentNotFound(CommentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidVerdictException.class)
    public ResponseEntity<ApiError> handleInvalidVerdict(InvalidVerdictException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler({LlmException.class, GitHubException.class, ValidationException.class})
    public ResponseEntity<ApiError> handleUpstreamFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError(HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
    }

    public record ApiError(int status, String message) {
    }
}
