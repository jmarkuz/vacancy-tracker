package com.vacancytracker.export;

import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One vacancy in the JSON backup, including its status history (backup schema version 1). */
public record BackupVacancy(
        UUID id,
        String title,
        String company,
        String url,
        String location,
        String country,
        String workMode,
        String description,
        String status,
        Integer priority,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String currency,
        String salaryPeriod,
        List<String> tags,
        String notes,
        String contact,
        Instant nextActionAt,
        String nextActionNote,
        Set<String> userEditedFields,
        Instant firstSeenAt,
        Instant lastSeenAt,
        /** When the source said the posting was published; null for backups made before it was stored. */
        Instant postedAt,
        List<BackupStatusEvent> statusHistory) {

    public static final int SCHEMA_VERSION = 1;

    static BackupVacancy from(Vacancy v, List<BackupStatusEvent> history) {
        return new BackupVacancy(v.getId(), v.getTitle(), v.getCompany(), v.getUrl(), v.getLocation(),
                v.getCountry(), v.getWorkMode(), v.getDescription(), v.getStatus().label(), v.getPriority(), v.getSalaryMin(),
                v.getSalaryMax(), v.getCurrency(), v.getSalaryPeriod(), v.getTags(), v.getNotes(), v.getContact(),
                v.getNextActionAt(), v.getNextActionNote(), v.getUserEditedFields(), v.getFirstSeenAt(),
                v.getLastSeenAt(), v.getPostedAt(), history);
    }

    public record BackupStatusEvent(String from, String to, Instant at, String note) {

        public VacancyStatus fromStatus() {
            return from == null ? null : VacancyStatus.fromLabel(from);
        }
    }
}
