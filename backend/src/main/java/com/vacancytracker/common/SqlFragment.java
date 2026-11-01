package com.vacancytracker.common;

import java.util.Map;

/**
 * A SQL predicate with named parameters. Only generated, validated SQL goes in here;
 * user values always travel as parameters.
 */
public record SqlFragment(String sql, Map<String, Object> params) {

    public static final SqlFragment ALL = new SqlFragment("TRUE", Map.of());
}
