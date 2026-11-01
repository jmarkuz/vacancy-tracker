package com.vacancytracker.web;

import com.vacancytracker.collection.NoJobPostingException;
import com.vacancytracker.collection.UrlFetchException;
import com.vacancytracker.matching.SavedViewNotFoundException;
import com.vacancytracker.vacancy.InvalidStatusTransitionException;
import com.vacancytracker.vacancy.VacancyNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain and validation errors to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(VacancyNotFoundException.class)
    ProblemDetail notFound(VacancyNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(com.vacancytracker.common.NotFoundException.class)
    ProblemDetail notFoundGeneric(com.vacancytracker.common.NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(SavedViewNotFoundException.class)
    ProblemDetail viewNotFound(SavedViewNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail conflict(InvalidStatusTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Invalid status transition", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "Conflicting data", "A vacancy with the same canonical URL already exists.");
    }

    @ExceptionHandler(NoJobPostingException.class)
    ProblemDetail noJobPosting(NoJobPostingException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "No job data on page", e.getMessage());
    }

    @ExceptionHandler(UrlFetchException.class)
    ProblemDetail fetchFailed(UrlFetchException e) {
        return problem(HttpStatus.BAD_GATEWAY, "Source unavailable", e.getMessage());
    }

    @ExceptionHandler(com.vacancytracker.matching.ProfileValidationException.class)
    ProblemDetail invalidProfile(com.vacancytracker.matching.ProfileValidationException e) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Profile is not valid",
                "One or more fields of the profile are invalid.");
        detail.setProperty("errors", e.errors());
        return detail;
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ProblemDetail unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body", "The request body is not valid JSON.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "One or more fields are invalid.");
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid input", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
