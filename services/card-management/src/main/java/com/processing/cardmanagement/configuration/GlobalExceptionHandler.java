package com.processing.cardmanagement.configuration;

import com.processing.cardmanagement.exceptions.*;
import com.processing.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadableBody(HttpMessageNotReadableException exception) {
        return new ErrorResponse("VALIDATION_ERROR", "Request body must be valid JSON",
                Instant.now(), serviceName, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidParameter(MethodArgumentTypeMismatchException exception) {
        return new ErrorResponse("VALIDATION_ERROR", exception.getName() + ": invalid parameter value",
                Instant.now(), serviceName, null);
    }

    @Value("${app.service-name}")
    private String serviceName;

    @ExceptionHandler(CardNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleCardNotFoundException(
        CardNotFoundException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleConstraintViolationException(
        ConstraintViolationException ex
    ) {
        var violation = ex.getConstraintViolations()
            .stream()
            .findFirst();

        if (violation.isEmpty()) {
            log.warn(ex.getMessage());
        } else {
            log.warn(
                "Message: {}, Invalid value: {}",
                ex.getMessage(),
                violation.get().getInvalidValue()
            );
        }

        return errorResponseFromException(ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMethodArgumentNotValidException(
        MethodArgumentNotValidException ex
    ) {
        var fieldError = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .findFirst();

        var errorMessage = fieldError
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Constraint violation");

        if (fieldError.isEmpty()) {
            log.warn(ex.getMessage());
        } else {
            log.warn(
                "Message: {}, Field: {}, Invalid value: {}",
                fieldError.get().getDefaultMessage(),
                fieldError.get().getField(),
                fieldError.get().getRejectedValue()
            );
        }

        return new ErrorResponse(
            ex.getClass().getSimpleName(),
            errorMessage,
            Instant.now(),
            serviceName,
            null
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgumentException(
        IllegalArgumentException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleIllegalStateException(
        IllegalStateException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    public ErrorResponse handleInsufficientFundsException(
        InsufficientFundsException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(TooLargeLimitException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ErrorResponse handleTooLargeLimitException(
        TooLargeLimitException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(RollbackAlreadySatisfiedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleRollbackAlreadySatisfiedException(
        RollbackAlreadySatisfiedException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(ReservationAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleRrnAlreadyExistsException(
        ReservationAlreadyExistsException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleRrnNotFoundException(
        ReservationNotFoundException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleException(
        Exception ex
    ) {
        log.error("Critical error: {}", ex.getMessage(), ex);
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(BinNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleBinNotFoundException(
        BinNotFoundException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(BinAlreadyExistException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleBinAlreadyExistException(
        BinAlreadyExistException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(CardGenerationLimitException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleCardGenerationLimitException(
        CardGenerationLimitException ex
    ) {
        log.warn(ex.getMessage());
        return errorResponseFromException(ex);
    }

    @ExceptionHandler(OutOfRetriesException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleOutOfRetriesException(
        OutOfRetriesException ex
    ) {
        log.error(ex.getMessage(), ex.getReason());
        return errorResponseFromException(ex);
    }

    private ErrorResponse errorResponseFromException(Exception ex) {
        return new ErrorResponse(
            ex.getClass().getSimpleName(),
            ex.getMessage(),
            Instant.now(),
            serviceName,
            null
        );
    }
}
