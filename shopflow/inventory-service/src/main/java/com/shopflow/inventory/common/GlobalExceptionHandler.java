package com.shopflow.inventory.common;

import java.util.List;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 problem+json responses. Extending
 * ResponseEntityExceptionHandler also turns validation errors into 400 problems.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Product not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, "Insufficient stock", ex.getMessage());
    }

    // two concurrent first-time requests for the same orderRef: the loser hits the unique key
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent request", "Request is already being processed, retry");
    }

    // @Valid @RequestBody failures: list which fields are wrong, e.g. "quantity: must be greater than or equal to 1"
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setProperty("errors", messages(ex.getAllErrors()));
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

    // constraints on @RequestParam / @RequestHeader (and the body, when both are present)
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setProperty("errors", messages(ex.getAllErrors()));
        return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }

    private static List<String> messages(List<? extends MessageSourceResolvable> errors) {
        return errors.stream()
                .map(e -> e instanceof FieldError fe ? fe.getField() + ": " + fe.getDefaultMessage() : e.getDefaultMessage())
                .sorted()
                .toList();
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
