package com.vacancytracker.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * A part of the JSON backup that another module owns (T-093): the export module writes it after the vacancies under
 * {@link #name()} and hands the same field back on restore, so the backup can carry data the export module does not
 * know, such as AI presets, without depending on that module.
 */
public interface BackupSection {

    /** The field of the backup object, for example "presets". */
    String name();

    /** Writes the field's value (the field name is already written). */
    void write(JsonGenerator gen) throws IOException;

    /** Restores from the field's value; returns how many records were restored. */
    int restore(JsonNode value);
}
