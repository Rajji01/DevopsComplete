package com.shopflow.ledger;

import java.util.List;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.shopflow.ledger.domain.AccountNotFoundException;
import com.shopflow.ledger.domain.UnbalancedEntryException;

/** Maps ledger exceptions to RFC 7807 problem+json responses. */
@RestControllerAdvice
public class LedgerExceptionHandler extends ResponseEntityExceptionHandler {

    // a well-formed request that cannot be posted: the business rule rejected it -> 422
    @ExceptionHandler(UnbalancedEntryException.class)
    ProblemDetail handleUnbalanced(UnbalancedEntryException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unbalanced entry", ex.getMessage());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail handleUnknownAccount(AccountNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown account", ex.getMessage());
    }

    // malformed request (e.g. an account/entry currency mismatch) -> 400
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setProperty("errors", messages(ex.getAllErrors()));
        return super.handleMethodArgumentNotValid(ex, headers, status, request);
    }

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
