package com.vacancytracker.collection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Extracts a schema.org JobPosting from the JSON-LD blocks of a page (T-023).
 * Pages without JobPosting data return empty; the user then adds the vacancy manually.
 */
public final class JobPostingExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobPostingExtractor() {
    }

    public record ExtractedJob(
            String title,
            String company,
            String location,
            String workMode,
            String description,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            String salaryPeriod) {
    }

    public static Optional<ExtractedJob> extract(String html) {
        Document doc = Jsoup.parse(html);
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode posting = parseBlock(script.data()).flatMap(JobPostingExtractor::findPosting).orElse(null);
            if (posting != null) {
                return Optional.of(toJob(posting));
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> parseBlock(String json) {
        try {
            return Optional.ofNullable(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Optional<JsonNode> findPosting(JsonNode node) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<JsonNode> found = findPosting(child);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (isJobPosting(node)) {
            return Optional.of(node);
        }
        return node.has("@graph") ? findPosting(node.get("@graph")) : Optional.empty();
    }

    private static boolean isJobPosting(JsonNode node) {
        JsonNode type = node.get("@type");
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("JobPosting".equals(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return "JobPosting".equals(type.asText());
    }

    private static ExtractedJob toJob(JsonNode posting) {
        JsonNode salary = posting.path("baseSalary");
        JsonNode value = salary.path("value");
        BigDecimal min = decimal(value.has("minValue") ? value.get("minValue") : value.get("value"));
        BigDecimal max = decimal(value.has("maxValue") ? value.get("maxValue") : null);
        String description = posting.hasNonNull("description")
                ? Jsoup.parse(posting.get("description").asText()).text() : null;
        return new ExtractedJob(
                text(posting, "title"),
                text(posting.path("hiringOrganization"), "name"),
                location(posting.get("jobLocation")),
                "TELECOMMUTE".equalsIgnoreCase(text(posting, "jobLocationType")) ? "remote" : null,
                description,
                min,
                max,
                text(salary, "currency"),
                period(text(value, "unitText")));
    }

    private static String location(JsonNode jobLocation) {
        JsonNode first = jobLocation != null && jobLocation.isArray() ? jobLocation.get(0) : jobLocation;
        JsonNode address = first == null ? null : first.get("address");
        if (address == null) {
            return null;
        }
        String city = text(address, "addressLocality");
        String country = text(address, "addressCountry");
        if (city == null) {
            return country;
        }
        return country == null ? city : city + ", " + country;
    }

    private static String period(String unit) {
        if (unit == null) {
            return null;
        }
        return switch (unit.toUpperCase(Locale.ROOT)) {
            case "YEAR" -> "year";
            case "MONTH" -> "month";
            case "HOUR" -> "hour";
            default -> null;
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
