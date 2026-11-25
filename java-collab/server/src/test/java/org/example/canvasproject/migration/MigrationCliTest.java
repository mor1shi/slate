package org.example.canvasproject.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверка аргументов до создания соединений с базами. */
class MigrationCliTest {

    @TempDir
    Path temp;

    @Test
    void rejectsMissingDirections() {
        assertEquals(2, MigrationCli.run(new String[]{}));
        assertEquals(2, MigrationCli.run(new String[]{"mongo"}));
    }

    @Test
    void rejectsSameDirection() {
        assertEquals(2, MigrationCli.run(new String[]{
                "mongo", "mongo", "--dir", temp.toString()}));
    }

    @Test
    void rejectsUnknownDirection() {
        assertEquals(2, MigrationCli.run(new String[]{
                "postgres", "sqlite", "--dir", temp.toString()}));
    }

    @Test
    void helpExitsSuccessfully() {
        assertEquals(0, MigrationCli.run(new String[]{"--help"}));
        assertEquals(0, MigrationCli.run(new String[]{"-h"}));
    }

    @Test
    void defaultDataDirectoryUsesXdgOrHome() {
        Path path = MigrationCli.defaultDataDirectory();
        assertTrue(path.isAbsolute());
        assertTrue(path.toString().endsWith("canvasproject"));
    }
}
