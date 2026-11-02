package db.migration;

import com.vacancytracker.vacancy.VisaSponsorship;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Fills vacancy.visa_sponsorship for vacancies stored before T-078 (ADR-044), with the same detector the app uses
 * from now on. The column existed since V1 but was never written. Runs once, like any migration.
 */
public class V12__Detect_visa_sponsorship extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement select = context.getConnection().createStatement();
             ResultSet rows = select.executeQuery("SELECT id, title, description FROM vacancy WHERE description IS NOT NULL");
             PreparedStatement update = context.getConnection().prepareStatement("UPDATE vacancy SET visa_sponsorship = ? WHERE id = ?")) {
            while (rows.next()) {
                Boolean offered = VisaSponsorship.detect(rows.getString("title"), rows.getString("description"));
                if (offered != null) {
                    update.setBoolean(1, offered);
                    update.setObject(2, rows.getObject("id"));
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }
}
