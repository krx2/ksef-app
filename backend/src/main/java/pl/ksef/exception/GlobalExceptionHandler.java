package pl.ksef.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.ksef.service.ksef.KsefException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    record ErrorResponse(String timestamp, int status, String error, String path) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException e,
                                                        HttpServletRequest request) {
        log.warn("Resource not found [{}]: {}", request.getRequestURI(), e.getMessage());
        return error(HttpStatus.NOT_FOUND, e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e,
                                                               HttpServletRequest request) {
        log.warn("Invalid argument [{}]: {}", request.getRequestURI(), e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ErrorResponse> handleIo(java.io.IOException e, HttpServletRequest request) {
        log.error("IO error [{}]: {}", request.getRequestURI(), e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e, HttpServletRequest request) {
        log.error("Runtime error [{}]: {}", request.getRequestURI(), e.getMessage(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(KsefException.class)
    public ResponseEntity<ErrorResponse> handleKsef(KsefException e, HttpServletRequest request) {
        log.error("KSeF error [{}]: {}", request.getRequestURI(), e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "KSeF API error: " + e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, msg, request.getRequestURI());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException e, HttpServletRequest request) {
        String msg = e.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("Constraint violation [{}]: {}", request.getRequestURI(), msg);
        return error(HttpStatus.BAD_REQUEST, msg, request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(LocalDateTime.now().toString(), status.value(), message, path));
    }
}
