package com.vacancytracker.matching;

import java.util.UUID;

/** One company on a list. profileId is null for a global entry; listType is allowlist or blocklist. */
public record CompanyListEntry(UUID id, String company, String listType, UUID profileId) {
}
