package com.vacancytracker.collection.mail;

/** One vacancy found in an alert email. company and location can be null when the email does not show them. */
public record ParsedJob(String externalId, String url, String title, String company, String location) {
}
