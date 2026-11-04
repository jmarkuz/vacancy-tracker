package com.vacancytracker.web;

import com.vacancytracker.application.documents.FactsNotConfirmedException;
import com.vacancytracker.application.documents.ResumeTooLongException;
import com.vacancytracker.application.resume.UnsupportedResumeException;
import com.vacancytracker.collection.NoJobPostingException;
import com.vacancytracker.collection.UrlFetchException;
import com.vacancytracker.matching.CriterionException;
import com.vacancytracker.matching.SavedViewNotFoundException;
import com.vacancytracker.vacancy.InvalidStatusTransitionException;
import com.vacancytracker.vacancy.VacancyNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain and validation errors to RFC 9457 problem details. It extends Spring's base handler so that framework
 * errors (ResponseStatusException, bad path or query values, missing parameters, unsupported media types) have the
 * same shape. The frontend reads {@code detail}, {@code title} and {@code errors}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        if (e instanceof DuplicateKeyException) {
            return problem(HttpStatus.CONFLICT, "Conflicting data", duplicateMessage(e));
        }
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Value not allowed", "A value is too long or not allowed.");
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

    /** AI is off or not configured: nothing was sent (ADR-041). */
    @ExceptionHandler(com.vacancytracker.application.ai.AiUnavailableException.class)
    ProblemDetail aiUnavailable(com.vacancytracker.application.ai.AiUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "AI is off", e.getMessage());
    }

    @ExceptionHandler(com.vacancytracker.application.ai.AiFailedException.class)
    ProblemDetail aiFailed(com.vacancytracker.application.ai.AiFailedException e) {
        return problem(HttpStatus.BAD_GATEWAY, "Claude gave no usable answer", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(e, problem(HttpStatus.BAD_REQUEST, "Malformed request body",
                "The request body is not valid JSON."), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", "One or more fields are invalid.");
        detail.setProperty("errors", errors);
        return handleExceptionInternal(e, detail, headers, HttpStatus.UNPROCESSABLE_ENTITY, request);
    }

    /** The text has facts the base draft does not have; the findings let the client show which. */
    @ExceptionHandler(FactsNotConfirmedException.class)
    ProblemDetail factsNotConfirmed(FactsNotConfirmedException e) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Facts not confirmed", e.getMessage());
        detail.setProperty("findings", e.findings());
        return detail;
    }

    @ExceptionHandler(ResumeTooLongException.class)
    ProblemDetail resumeTooLong(ResumeTooLongException e) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Resume is too long", e.getMessage());
        detail.setProperty("pages", e.pages());
        return detail;
    }

    @ExceptionHandler(UnsupportedResumeException.class)
    ProblemDetail unsupportedResume(UnsupportedResumeException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Resume cannot be read", e.getMessage());
    }

    @ExceptionHandler(CriterionException.class)
    ProblemDetail invalidCriterion(CriterionException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid criterion", e.getMessage());
    }

    /** Last resort for the hand-written checks that throw IllegalArgumentException; logged, as it may be a bug. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        LOG.warn("Unclassified invalid input answered as 422", e);
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid input", e.getMessage());
    }

    /** Anything unexpected: logged with the stack, answered without internals. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) throws Exception {
        if (AnnotatedElementUtils.findMergedAnnotation(e.getClass(), ResponseStatus.class) != null) {
            throw e;
        }
        LOG.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error", "Something went wrong on the server. Try again."));
    }

    /** Names the duplicate by the violated constraint; an unknown constraint gets a generic message. */
    private static String duplicateMessage(DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (cause.contains("vacancy_canonical_url_uk")) {
            return "A vacancy with the same canonical URL already exists.";
        }
        if (cause.contains("vacancy_source_external_uk")) {
            return "This posting is already known from the same source.";
        }
        if (cause.contains("tailored_document_vacancy_id_kind_version_key")) {
            return "This document version already exists. Reload and try again.";
        }
        if (cause.contains("ai_preset_name_uk")) {
            return "A preset with this name already exists.";
        }
        if (cause.contains("company_list_unique")) {
            return "This company is already on the list.";
        }
        if (cause.contains("saved_view_name_key")) {
            return "A saved view with this name already exists.";
        }
        if (cause.contains("search_profile_name_key")) {
            return "A profile with this name already exists.";
        }
        return "This already exists.";
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
