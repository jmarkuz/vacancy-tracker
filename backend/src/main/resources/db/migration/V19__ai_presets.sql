-- AI presets (T-093, ADR-047): the owner's search goal, parameter weights, document prompts and base documents.
-- The base resume is a row of the resume table, not a copy: the Resume screen and the preset share it. The active
-- resume is the base resume of the default preset. The Default preset itself is created by the application from
-- the texts in resources/presets/default, so the defaults live in resources, not in this script.
CREATE TABLE ai_preset (
    id                           UUID        PRIMARY KEY,
    name                         TEXT        NOT NULL,
    is_default                   BOOLEAN     NOT NULL DEFAULT FALSE,
    main_goal                    TEXT        NOT NULL,
    weights                      JSONB       NOT NULL,
    resume_prompt                TEXT        NOT NULL,
    cover_letter_prompt          TEXT        NOT NULL,
    base_resume_id               UUID        REFERENCES resume (id) ON DELETE SET NULL,
    base_cover_letter            TEXT        NOT NULL,
    base_cover_letter_file_name  TEXT,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX ai_preset_name_uk ON ai_preset (lower(name));
CREATE UNIQUE INDEX ai_preset_one_default_uk ON ai_preset (is_default) WHERE is_default;
