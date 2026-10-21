package db.migration;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import pl.piomin.services.deskpilot.agent.specialist.Specialist;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistDefaults;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specialist agents editable from the UI (13g): the definitions move from {@code specialists/specialists.json} to the
 * {@code specialist} table, seeded once from that file so routing behaves as before. Keywords, tools and examples are
 * JSON arrays; a null {@code tools} means every tool.
 */
public class V22__Specialists extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
                    create table specialist (
                        id          varchar(40)  primary key,
                        name        varchar(100) not null,
                        description varchar(500) not null,
                        keywords    text         not null,
                        tools       text,
                        prompt      text         not null,
                        examples    text         not null,
                        enabled     boolean      not null default true,
                        sort_order  integer      not null default 0,
                        updated_by  varchar(64)  not null,
                        updated_at  timestamp(6) with time zone not null
                    )""");
        }
        JsonMapper json = JsonMapper.builder().build();
        List<Specialist> defaults = SpecialistDefaults.load(json);
        try (PreparedStatement insert = context.getConnection().prepareStatement("""
                insert into specialist (id, name, description, keywords, tools, prompt, examples, enabled, sort_order,
                                        updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, true, ?, 'system', ?)""")) {
            Timestamp now = Timestamp.from(Instant.now());
            for (int i = 0; i < defaults.size(); i++) {
                Specialist specialist = defaults.get(i);
                insert.setString(1, specialist.id());
                insert.setString(2, specialist.name());
                insert.setString(3, specialist.description());
                insert.setString(4, json.writeValueAsString(specialist.keywords()));
                insert.setString(5, specialist.tools() == null ? null : json.writeValueAsString(specialist.tools()));
                insert.setString(6, specialist.prompt());
                insert.setString(7, json.writeValueAsString(specialist.examples()));
                insert.setInt(8, (i + 1) * 10);
                insert.setTimestamp(9, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
