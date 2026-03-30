package pl.ksef.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.ksef.service.ksef.KsefException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIo(IOException e) {
        log.error("IO error: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        // TODO: Wszystkie RuntimeException (włącznie z "Invoice not found", "User not found")
        //       zwracają HTTP 400, co jest semantycznie błędne — zasoby nieznalezione to HTTP 404.
        //       Należy stworzyć dedykowaną klasę ResourceNotFoundException extends RuntimeException
        //       i obsłużyć ją osobnym handlerem zwracającym HttpStatus.NOT_FOUND.
        //       Przykład: invoiceRepository.findById(id).orElseThrow(ResourceNotFoundException::new)
        log.error("Runtime error: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(KsefException.class)
    public ResponseEntity<Map<String, Object>> handleKsef(KsefException e) {
        log.error("KSeF error: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "KSeF API error: " + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        // TODO: Odpowiedź błędu nie zawiera pola "path" (ścieżka URL żądania).
        //       Frontend nie może wyświetlić kontekstu błędu, a logi są trudniejsze do diagnozowania.
        //       Aby dodać path, wstrzyknąć HttpServletRequest do handlera i użyć request.getRequestURI().
        //       Wzorzec: Map.of("path", request.getRequestURI(), "timestamp", ..., "status", ..., "error", ...)
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", message
        ));
    }
}
