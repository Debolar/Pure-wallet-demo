package dev.purse;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into JSON like {"detail": "Insufficient funds"}. */
@RestControllerAdvice
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("detail", e.getMessage()));
    }

    // The client sent something we can't read: broken JSON, or text where a number was expected.
    // That's the client's mistake (400), not a server failure (500).
    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class})
    public ResponseEntity<Map<String, String>> handleUnreadable(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("detail", "Invalid request: check the JSON body and the values you sent"));
    }

    // Never show internal details (SQL, stack traces) to the client; log them instead.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        // Other Spring errors (unknown URL, wrong HTTP method) already know their status
        if (e instanceof ErrorResponse springError) {
            int code = springError.getStatusCode().value();
            String detail = code == 404 ? "Not found" : "Invalid request";
            return ResponseEntity.status(springError.getStatusCode()).body(Map.of("detail", detail));
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Something went wrong. Please try again."));
    }
}
