package com.example.weather.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.entity.User;
import com.example.weather.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL + PostGIS에서 마이그레이션과 JPA 매핑이 맞는지 확인한다 (ddl-auto=validate로 앱 컨텍스트가 뜨는 것 자체도 검증). */
class DatabaseMigrationTest extends PostgisIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserRepository users;

    @Test
    void migrationsAreAppliedInOrderWithoutFailures() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertTrue(versions.size() >= 4, versions.toString());
        assertEquals(List.of("1", "2", "3", "4"), versions.subList(0, 4));
    }

    @Test
    void postgisAndTrigramExtensionsAreInstalled() {
        assertNotNull(jdbc.queryForObject("select postgis_version()", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Integer.class));
    }

    @Test
    void locationsTableHasSpatialAndTrigramIndexes() {
        List<String> defs = jdbc.queryForList(
                "select indexdef from pg_indexes where tablename = 'locations'", String.class);
        assertTrue(defs.stream().anyMatch(d -> d.contains("USING gist (geom)")), defs.toString());
        assertTrue(defs.stream().anyMatch(d -> d.contains("USING gin (full_name gin_trgm_ops)")), defs.toString());
    }

    @Test
    void userCanBeSavedAndEmailMustBeUnique() {
        User u = new User();
        u.setEmail("migration-test@example.com");
        u.setPasswordHash("hash");
        u.setName("테스터");
        User saved = users.saveAndFlush(u);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());

        User dup = new User();
        dup.setEmail("migration-test@example.com");
        dup.setPasswordHash("hash2");
        dup.setName("중복");
        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(dup));
    }
}
