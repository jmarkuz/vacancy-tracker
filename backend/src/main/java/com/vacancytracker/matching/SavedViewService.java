package com.vacancytracker.matching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vacancytracker.matching.SavedViewRepository.Row;
import com.vacancytracker.vacancy.VacancySort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved views (T-021 backend): validated on save, applied through the same compiler as the list endpoint. */
@Service
public class SavedViewService {

    private final SavedViewRepository repository;
    private final CriterionCompiler compiler;
    private final ObjectMapper mapper;

    public SavedViewService(SavedViewRepository repository, CriterionCompiler compiler, ObjectMapper mapper) {
        this.repository = repository;
        this.compiler = compiler;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<SavedView> list() {
        return repository.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public SavedView get(UUID id) {
        return repository.findById(id).map(this::toView).orElseThrow(() -> new SavedViewNotFoundException(id));
    }

    @Transactional
    public SavedView create(String name, List<Criterion> criteria, String sort, List<String> columns) {
        Row row = validated(UUID.randomUUID(), name, criteria, sort, columns);
        repository.insert(row);
        return toView(row);
    }

    @Transactional
    public SavedView update(UUID id, String name, List<Criterion> criteria, String sort, List<String> columns) {
        get(id);
        Row row = validated(id, name, criteria, sort, columns);
        repository.update(row);
        return toView(row);
    }

    @Transactional
    public void delete(UUID id) {
        if (repository.delete(id) == 0) {
            throw new SavedViewNotFoundException(id);
        }
    }

    /** Criteria are compiled again at read time; a stored view can never run unchecked SQL. */
    @Transactional(readOnly = true)
    public com.vacancytracker.common.SqlFragment compileFor(SavedView view) {
        return compiler.compile(view.criteria());
    }

    private Row validated(UUID id, String name, List<Criterion> criteria, String sort, List<String> columns) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        List<Criterion> safe = criteria == null ? List.of() : criteria;
        compiler.validate(safe);
        VacancySort.parse(sort);
        try {
            return new Row(id, name.trim(), mapper.writeValueAsString(safe), sort,
                    columns == null ? List.of() : List.copyOf(columns));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Criteria could not be stored");
        }
    }

    private SavedView toView(Row row) {
        try {
            List<Criterion> criteria = compiler.parse(row.criteriaJson());
            return new SavedView(row.id(), row.name(), criteria, row.sort(), row.columns());
        } catch (CriterionException e) {
            throw new IllegalStateException("Stored view has invalid criteria: " + row.id(), e);
        }
    }
}
