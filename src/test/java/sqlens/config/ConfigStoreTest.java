package sqlens.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {

    @TempDir
    Path dir;

    @Test
    void readsDatasources() throws Exception {
        Path file = dir.resolve("datasources.json");
        Files.writeString(file, """
                {"datasources": [
                  {"name": "pg", "url": "jdbc:postgresql://localhost/app", "user": "app", "password": "pw",
                   "properties": {"ssl": "false"}, "readOnly": true, "queryTimeoutSeconds": 30},
                  {"name": "h2", "url": "jdbc:h2:mem:x", "driver": "org.h2.Driver"}
                ]}
                """);
        ConfigStore store = new ConfigStore(file);

        List<DataSourceConfig> list = store.datasources();
        assertNull(store.error());
        assertEquals(2, list.size());
        DataSourceConfig pg = list.get(0);
        assertEquals("pg", pg.name());
        assertEquals("app", pg.user());
        assertEquals("false", pg.properties().get("ssl"));
        assertTrue(pg.readOnly());
        assertEquals(30, pg.queryTimeoutSeconds());
        assertNull(pg.driver());
        assertEquals("org.h2.Driver", store.find("h2").orElseThrow().driver());
    }

    @Test
    void reloadsWhenFileChanges() throws Exception {
        Path file = dir.resolve("datasources.json");
        Files.writeString(file, "{\"datasources\": [{\"name\": \"a\", \"url\": \"jdbc:x\"}]}");
        ConfigStore store = new ConfigStore(file);
        assertEquals(1, store.datasources().size());

        Files.writeString(file, "{\"datasources\": [{\"name\": \"a\", \"url\": \"jdbc:x\"}, {\"name\": \"b\", \"url\": \"jdbc:y\"}]}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertEquals(2, store.datasources().size());
    }

    @Test
    void reportsMissingAndInvalidFiles() throws Exception {
        Path file = dir.resolve("datasources.json");
        ConfigStore store = new ConfigStore(file);
        assertTrue(store.datasources().isEmpty());
        assertNotNull(store.error());

        Files.writeString(file, "{\"datasources\": [{\"name\": \"a\", \"url\": \"jdbc:x\"}, {\"name\": \"a\", \"url\": \"jdbc:y\"}]}");
        assertTrue(store.datasources().isEmpty());
        assertTrue(store.error().contains("duplicate"), store.error());

        Files.writeString(file, "{not json");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertTrue(store.datasources().isEmpty());
        assertNotNull(store.error());
    }

    @Test
    void readsGridRowCap() throws Exception {
        Path file = dir.resolve("datasources.json");
        ConfigStore store = new ConfigStore(file);
        assertEquals(ConfigStore.DEFAULT_MAX_GRID_ROWS, store.maxGridRows());

        Files.writeString(file, "{\"datasources\": []}");
        assertEquals(ConfigStore.DEFAULT_MAX_GRID_ROWS, store.maxGridRows());

        Files.writeString(file, "{\"settings\": {\"maxGridRows\": 0}, \"datasources\": []}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        assertEquals(0, store.maxGridRows());

        Files.writeString(file, "{\"settings\": {\"maxGridRows\": -1}, \"datasources\": []}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)));
        assertEquals(ConfigStore.DEFAULT_MAX_GRID_ROWS, store.maxGridRows());
        assertTrue(store.error().contains("maxGridRows"), store.error());
    }

    @Test
    void rejectsUnknownKeysWithAHint() throws Exception {
        Path file = dir.resolve("datasources.json");
        Files.writeString(file, """
                {"setings": {},
                 "datasources": [
                   {"name": "chinook", "url": "jdbc:ctree:6597@localhost:chinook", "readonly": true},
                   {"url": "jdbc:x", "pasword": "x", "colour": "blue"}
                 ]}
                """);
        ConfigStore store = new ConfigStore(file);
        assertTrue(store.datasources().isEmpty());
        String error = store.error();
        assertTrue(error.contains("unknown field \"readonly\" in datasource \"chinook\", did you mean \"readOnly\"?"), error);
        assertTrue(error.contains("unknown field \"setings\", did you mean \"settings\"?"), error);
        assertTrue(error.contains("unknown field \"pasword\" in datasource #2, did you mean \"password\"?"), error);
        assertTrue(error.contains("unknown field \"colour\" in datasource #2 (known fields: name,"), error);
    }

    @Test
    void reportsWrongTypesWithTheirPath() throws Exception {
        Path file = dir.resolve("datasources.json");
        Files.writeString(file, "{\"datasources\": [{\"name\": \"a\", \"url\": \"jdbc:x\", \"readOnly\": \"maybe\"}]}");
        ConfigStore store = new ConfigStore(file);
        assertTrue(store.datasources().isEmpty());
        assertTrue(store.error().contains("\"datasources[0].readOnly\""), store.error());
        assertFalse(store.error().contains("REDACTED"), store.error());
    }

    @Test
    void expandsPlaceholders() {
        System.setProperty("sqlens.test.secret", "s3cret");
        try {
            assertEquals("pw=s3cret", ConfigStore.expand("pw=${sqlens.test.secret}"));
            assertEquals("${sqlens.test.unknown}", ConfigStore.expand("${sqlens.test.unknown}"));
            assertFalse(ConfigStore.expand("${PATH}").contains("${"));
        } finally {
            System.clearProperty("sqlens.test.secret");
        }
    }
}
