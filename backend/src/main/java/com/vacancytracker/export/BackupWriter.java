package com.vacancytracker.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.common.BackupSection;
import com.vacancytracker.export.BackupVacancy.BackupStatusEvent;
import com.vacancytracker.vacancy.StatusEvent;
import com.vacancytracker.vacancy.Vacancy;
import com.vacancytracker.vacancy.VacancyService;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Full JSON backup of all vacancies with their status history, then the other modules' sections (schema version 1). */
@Service
public class BackupWriter {

    private final VacancyExporter exporter;
    private final VacancyService service;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ObjectProvider<BackupSection> sections;

    public BackupWriter(VacancyExporter exporter, VacancyService service, ObjectMapper mapper, Clock clock,
                        ObjectProvider<BackupSection> sections) {
        this.exporter = exporter;
        this.service = service;
        this.mapper = mapper;
        this.clock = clock;
        this.sections = sections;
    }

    public void write(OutputStream out) throws IOException {
        try (JsonGenerator gen = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
            gen.writeStartObject();
            gen.writeNumberField("schemaVersion", BackupVacancy.SCHEMA_VERSION);
            gen.writeStringField("exportedAt", clock.instant().toString());
            gen.writeArrayFieldStart("vacancies");
            exporter.forEach(null, (Vacancy v) -> {
                try {
                    mapper.writeValue(gen, BackupVacancy.from(v, historyOf(v)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            gen.writeEndArray();
            for (BackupSection section : sections.orderedStream().toList()) {
                gen.writeFieldName(section.name());
                section.write(gen);
            }
            gen.writeEndObject();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private List<BackupStatusEvent> historyOf(Vacancy v) {
        return service.history(v.getId()).stream().map(this::toBackup).toList();
    }

    private BackupStatusEvent toBackup(StatusEvent e) {
        return new BackupStatusEvent(e.from() == null ? null : e.from().label(), e.to().label(), e.at(), e.note());
    }
}
