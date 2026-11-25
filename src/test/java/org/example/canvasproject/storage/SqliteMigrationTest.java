package org.example.canvasproject.storage;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Старая локальная схема (одна таблица boards с elements_json) должна
 * открываться без потери досок: элементы переезжают в отдельную таблицу.
 */
class SqliteMigrationTest {

    @TempDir
    Path temp;

    @Test
    void legacyBoardsAreMigratedToVersionTwo() throws Exception {
        Path file = temp.resolve("legacy.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE boards (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        elements_json TEXT NOT NULL DEFAULT '[]'
                    )
                    """);
            String json = new org.json.JSONArray()
                    .put(new JSONObject().put("elementId", "old-1")
                            .put("elementType", "note")
                            .put("data", new JSONObject().put("text", "старая заметка")))
                    .toString();
            st.execute("INSERT INTO boards VALUES('b-old','Старая доска',800,600,1,2,'"
                    + json.replace("'", "''") + "')");
            st.execute("PRAGMA user_version=1");
        }

        try (SqliteStorage storage = new SqliteStorage(file)) {
            BoardRecord board = storage.boards().find("b-old").orElseThrow();
            assertEquals("Старая доска", board.name());
            var elements = storage.elements().list("b-old");
            assertEquals(1, elements.size());
            assertEquals("старая заметка", elements.get(0).data().getString("text"));
            assertTrue(storage.boards().find("b-old").isPresent());
        }
    }
}
