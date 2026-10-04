package com.shopflow.order.common;

import java.util.List;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

/** RFC 7807 problem+json for every error; validation errors are handled by the parent class. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage());
    }

    @ExceptionHandler(OrderNotCancellableException.class)
    ProblemDetail handleNotCancellable(OrderNotCancellableException ex) {
        return problem(HttpStatus.CONFLICT, "Order not cancellable", ex.getMessage());
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", ex.getMessage());
        problem.setProperty("orderId", ex.getOrderId());
        return problem;
    }

    // e.g. cancel while inventory-service is down
    @ExceptionHandler({RestClientException.class, CallNotPermittedException.class})
    ProblemDetail handleDownstream(Exception ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", "Try again later");
    }

    // two concurrent first-time requests with the same Idempotency-Key
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent request", "Request is already being processed, retry");
    }

    // @Version mismatch: e.g. the same order cancelled twice at the same moment; the loser gets 409
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification", "Order was modified by another request, reload and retry");
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

    @ExceptionHandler(RequestNotPermitted.class)
    ProblemDetail handleRateLimited(RequestNotPermitted ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Rate limit exceeded, slow down");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
