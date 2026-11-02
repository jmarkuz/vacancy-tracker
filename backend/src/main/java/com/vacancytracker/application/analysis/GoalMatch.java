package com.vacancytracker.application.analysis;

import java.util.List;

/**
 * How well the vacancy fits the owner's goal (T-095): AI rates each weighted parameter of the preset 0..100 with an
 * explanation, and code computes the weighted average with the normalised weights. A deal-breaker is reported apart
 * from the score. Generating documents never changes it.
 */
public record GoalMatch(int score, List<Parameter> parameters, boolean dealBreaker, String dealBreakerReason) {

    /** share is the parameter's part of the total weight (0..1). */
    public record Parameter(String name, int weight, double share, int score, String explanation) {
    }

    static int score(List<Parameter> parameters) {
        double sum = parameters.stream().mapToDouble(p -> p.share() * p.score()).sum();
        return (int) Math.round(sum);
    }
}
