package io.github.sye1321.paylab.provider;

import io.github.sye1321.paylab.run.TestRunFinalizedException;
import io.github.sye1321.paylab.run.TestRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = PaymentController.class)
public class PaymentHttpErrors {

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ApiError> paymentNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PAYMENT_NOT_FOUND", "Provider payment not found"));
    }

    @ExceptionHandler(TestRunNotFoundException.class)
    ResponseEntity<ApiError> runNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("TEST_RUN_NOT_FOUND", "Test run not found"));
    }

    @ExceptionHandler(TestRunFinalizedException.class)
    ResponseEntity<ApiError> runFinalized() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("TEST_RUN_FINALIZED", "Test run is finalized and does not accept new payment operations"));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("IDEMPOTENCY_CONFLICT", "Idempotency key already belongs to a different payment intent"));
    }

    @ExceptionHandler(IllegalPaymentTransitionException.class)
    ResponseEntity<ApiError> illegalTransition(IllegalPaymentTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ILLEGAL_PAYMENT_TRANSITION", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_PAYMENT_REQUEST", ex.getMessage() != null ? ex.getMessage() : "Invalid payment request"));
    }

    public record ApiError(String code, String message) {
    }
}

