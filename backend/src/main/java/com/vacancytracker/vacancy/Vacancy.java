package com.vacancytracker.vacancy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A vacancy aggregate. Automation changes it only through {@link #applyAutomated(AutomatedFields)};
 * user changes go through the explicit methods, which record edited fields (ADR-012).
 */
public class Vacancy {

    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_SALARY = "salary";

    private final UUID id;
    private final String title;
    private final String company;
    private final String url;
    private final String canonicalUrl;
    private String location;
    private String country;
    private String workMode;
    private String description;
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String currency;
    private String salaryPeriod;
    private VacancyStatus status;
    private Integer priority;
    private String notes;
    private String contact;
    private String nextActionNote;
    private Instant nextActionAt;
    private List<String> tags;
    private final Set<String> userEditedFields;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private boolean isNew;

    private Vacancy(UUID id, String title, String company, String url, String canonicalUrl, String description,
                    VacancyStatus status, Instant firstSeenAt, Instant lastSeenAt) {
        this.id = id;
        this.title = title;
        this.company = company;
        this.url = url;
        this.canonicalUrl = canonicalUrl;
        this.description = description;
        this.status = status;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.isNew = true;
        this.tags = new ArrayList<>();
        this.userEditedFields = new LinkedHashSet<>();
    }

    /** A vacancy discovered by automation: starts in the Inbox with status New. */
    public static Vacancy discovered(UUID id, String title, String company, String url, String canonicalUrl,
                                     String description, Instant seenAt) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (company == null || company.isBlank()) {
            throw new IllegalArgumentException("company is required");
        }
        return new Vacancy(id, title, company, url, canonicalUrl, description, VacancyStatus.NEW, seenAt, seenAt);
    }

    /** Placement is set by the user or by an importer; automation does not change it. */
    public void setPlacement(String newLocation, String newCountry, String newWorkMode) {
        location = newLocation;
        country = newCountry;
        workMode = newWorkMode;
    }

    /** Rebuilds a vacancy from its stored state. Used by the repository only. */
    public static Vacancy restore(VacancyState state) {
        Vacancy v = new Vacancy(state.id(), state.title(), state.company(), state.url(), state.canonicalUrl(),
                state.description(), state.status(), state.firstSeenAt(), state.lastSeenAt());
        v.location = state.location();
        v.country = state.country();
        v.workMode = state.workMode();
        v.salaryMin = state.salaryMin();
        v.salaryMax = state.salaryMax();
        v.currency = state.currency();
        v.salaryPeriod = state.salaryPeriod();
        v.priority = state.priority();
        v.notes = state.notes();
        v.contact = state.contact();
        v.nextActionNote = state.nextActionNote();
        v.nextActionAt = state.nextActionAt();
        v.tags = new ArrayList<>(state.tags());
        v.userEditedFields.addAll(state.userEditedFields());
        v.isNew = state.isNew();
        return v;
    }

    public VacancyState toState() {
        return new VacancyState(id, title, company, url, canonicalUrl, location, country, workMode, description, salaryMin, salaryMax, currency,
                salaryPeriod, status, priority, notes, contact, nextActionNote, nextActionAt, List.copyOf(tags),
                Set.copyOf(userEditedFields), firstSeenAt, lastSeenAt, isNew);
    }

    /**
     * Automated update: refreshes lastSeenAt and writes description and salary
     * only if the user has not edited them. Never touches status, notes, tags,
     * priority, next action or contact.
     */
    public void applyAutomated(AutomatedFields fields) {
        if (fields.seenAt() != null && (lastSeenAt == null || fields.seenAt().isAfter(lastSeenAt))) {
            lastSeenAt = fields.seenAt();
        }
        if (!userEditedFields.contains(FIELD_DESCRIPTION) && fields.description() != null) {
            description = fields.description();
        }
        if (!userEditedFields.contains(FIELD_SALARY)) {
            salaryMin = fields.salaryMin();
            salaryMax = fields.salaryMax();
            currency = fields.currency();
            salaryPeriod = fields.salaryPeriod();
        }
    }

    /** User changes the status. Records the transition as a StatusEvent. */
    public StatusEvent changeStatus(VacancyStatus to, Instant at, String note) {
        if (!status.canMoveTo(to)) {
            throw new InvalidStatusTransitionException(status, to);
        }
        StatusEvent event = new StatusEvent(id, status, to, at, note);
        status = to;
        isNew = false;
        return event;
    }

    public void editDescription(String value) {
        description = value;
        userEditedFields.add(FIELD_DESCRIPTION);
    }

    public void editSalary(BigDecimal min, BigDecimal max, String newCurrency, String period) {
        salaryMin = min;
        salaryMax = max;
        currency = newCurrency;
        salaryPeriod = period;
        userEditedFields.add(FIELD_SALARY);
    }

    public void setPriority(Integer value) {
        if (value != null && (value < 1 || value > 3)) {
            throw new IllegalArgumentException("priority must be between 1 and 3");
        }
        priority = value;
    }

    public void setNotes(String value) {
        notes = value;
    }

    public void setContact(String value) {
        contact = value;
    }

    public void setNextAction(Instant at, String note) {
        nextActionAt = at;
        nextActionNote = note;
    }

    public void setTags(List<String> value) {
        tags = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getCompany() {
        return company;
    }

    public String getUrl() {
        return url;
    }

    public String getLocation() {
        return location;
    }

    public String getCountry() {
        return country;
    }

    public String getWorkMode() {
        return workMode;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getSalaryMin() {
        return salaryMin;
    }

    public BigDecimal getSalaryMax() {
        return salaryMax;
    }

    public String getCurrency() {
        return currency;
    }

    public String getSalaryPeriod() {
        return salaryPeriod;
    }

    public VacancyStatus getStatus() {
        return status;
    }

    public Integer getPriority() {
        return priority;
    }

    public String getNotes() {
        return notes;
    }

    public String getContact() {
        return contact;
    }

    public String getNextActionNote() {
        return nextActionNote;
    }

    public Instant getNextActionAt() {
        return nextActionAt;
    }

    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    public Set<String> getUserEditedFields() {
        return Collections.unmodifiableSet(userEditedFields);
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isNew() {
        return isNew;
    }
}
