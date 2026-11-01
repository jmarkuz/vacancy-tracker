package com.vacancytracker.matching;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CompanyListRepository {

    private final JdbcClient jdbc;

    CompanyListRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<CompanyListEntry> findAll() {
        return jdbc.sql("SELECT id, company, list_type, profile_id FROM company_list ORDER BY list_type, lower(company)")
                .query((rs, n) -> new CompanyListEntry(rs.getObject("id", UUID.class), rs.getString("company"),
                        rs.getString("list_type"), rs.getObject("profile_id", UUID.class)))
                .list();
    }

    /** Inserts unless the same company is already on that list in that scope. Returns whether a row was added. */
    boolean insertIfAbsent(CompanyListEntry e) {
        return jdbc.sql("INSERT INTO company_list (id, company, list_type, profile_id) VALUES (:id, :company, :type, :profile)"
                        + " ON CONFLICT DO NOTHING")
                .param("id", e.id())
                .param("company", e.company())
                .param("type", e.listType())
                .param("profile", e.profileId())
                .update() > 0;
    }

    boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM company_list WHERE id = :id").param("id", id).update() > 0;
    }

    void deleteForProfile(UUID profileId) {
        jdbc.sql("DELETE FROM company_list WHERE profile_id = :p").param("p", profileId).update();
    }
}
