package com.processing.authorization.controller;

import com.processing.common.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/** Отделяет ошибки входных HTTP-данных от бизнес-отказов и ошибок инфраструктуры. */
@RestControllerAdvice(assignableTypes = AuthControllerImpl.class)
public class RequestValidationHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidFields(MethodArgumentNotValidException exception) {
        String message = exception.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted().collect(Collectors.joining("; "));
        return new ErrorResponse("VALIDATION_ERROR", message, Instant.now(), "authorization", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadableBody(HttpMessageNotReadableException exception) {
        return new ErrorResponse("VALIDATION_ERROR", "Request body must be valid JSON",
                Instant.now(), "authorization", null);
    }
}
