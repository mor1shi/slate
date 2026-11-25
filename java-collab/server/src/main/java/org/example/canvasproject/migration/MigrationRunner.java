package org.example.canvasproject.migration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Оркестратор переноса: читает источник, делает резервную копию назначения,
 * пишет манифест, записывает данные, сверяет счётчики и содержимое.
 *
 * <p>Манифест обновляется до и после записи. Он не содержит кодов приглашений,
 * ключей устройств и паролей — только счётчики, пути и текстовые проблемы.
 */
public final class MigrationRunner {

    public static final String MANIFEST_NAME = "migration-manifest.json";

    private final MigrationStore source;
    private final MigrationStore target;
    private final Path workDirectory;

    public MigrationRunner(MigrationStore source, MigrationStore target, Path workDirectory) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("Нужны источник и назначение");
        }
        if (workDirectory == null) {
            throw new IllegalArgumentException("Не указан каталог для манифеста и копий");
        }
        this.source = source;
        this.target = target;
        this.workDirectory = workDirectory.toAbsolutePath();
    }

    public Report run() {
        try {
            Files.createDirectories(workDirectory);
        } catch (IOException e) {
            throw new MigrationException("Не удалось создать каталог отчёта: "
                    + workDirectory, e);
        }

        Path manifest = workDirectory.resolve(MANIFEST_NAME);
        Instant startedAt = Instant.now();

        MigrationData data = source.read();

        // Копия снимается до любого чтения/миграции схемы назначения.
        List<Path> backups;
        try {
            backups = target.backup(workDirectory.resolve("backups"));
        } catch (IOException | RuntimeException e) {
            List<String> problems = List.of("Резервная копия не создана: "
                    + readable(e.getMessage()));
            writeManifest(manifest, "failed", startedAt, Instant.now(), data,
                    null, null, null, List.of(), data.warnings(), problems);
            throw new MigrationException("Не удалось создать резервную копию: "
                    + readable(e.getMessage()), e);
        }

        Map<String, Integer> targetBefore;
        try {
            targetBefore = target.counts();
        } catch (RuntimeException e) {
            String message = readable(e.getMessage());
            writeManifest(manifest, "failed", startedAt, Instant.now(), data,
                    null, null, null, backups, data.warnings(),
                    List.of("Не удалось прочитать назначение: " + message));
            throw new MigrationException("Не удалось прочитать назначение: "
                    + message, e);
        }

        writeManifest(manifest, "running", startedAt, null, data, targetBefore,
                null, null, backups, data.warnings(), List.of());

        try {
            WriteResult written = target.write(data);
            MigrationData after = target.read();
            MigrationVerification.Result verification = MigrationVerification.compare(
                    data, after, written.boardIds(), written.userIds());
            List<String> warnings = new ArrayList<>(data.warnings());
            warnings.addAll(written.warnings());

            Report report = new Report(verification.ok(), manifest, backups,
                    targetBefore, data.counts(), after.counts(),
                    verification.verified(), written,
                    verification.problems(), List.copyOf(warnings));
            writeManifest(manifest, report.ok() ? "ok" : "failed", startedAt,
                    Instant.now(), data, targetBefore, after.counts(),
                    verification.verified(), backups, warnings,
                    verification.problems());
            return report;
        } catch (RuntimeException e) {
            String message = readable(e.getMessage());
            writeManifest(manifest, "failed", startedAt, Instant.now(), data,
                    targetBefore, null, null, backups, data.warnings(),
                    List.of("Запись прервана: " + message));
            throw new MigrationException("Запись прервана: " + message, e);
        }
    }

    private void writeManifest(Path file, String status, Instant startedAt,
                               Instant finishedAt, MigrationData data,
                               Map<String, Integer> targetBefore,
                               Map<String, Integer> targetAfter,
                               Map<String, Integer> verified,
                               List<Path> backups, List<String> warnings,
                               List<String> problems) {
        JSONObject root = new JSONObject();
        root.put("tool", "slate-migration");
        root.put("formatVersion", 1);
        root.put("status", status);
        root.put("startedAt", startedAt.toString());
        if (finishedAt != null) root.put("finishedAt", finishedAt.toString());
        root.put("source", source.description());
        root.put("target", target.description());
        root.put("backup", paths(backups));
        root.put("targetBefore", counts(targetBefore));
        root.put("sourceCounts", counts(data.counts()));
        if (targetAfter != null) root.put("targetAfter", counts(targetAfter));
        if (verified != null) root.put("verified", counts(verified));
        root.put("warnings", strings(warnings));
        root.put("problems", strings(problems));

        try {
            Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException("Не удалось записать манифест " + file, e);
        }
    }

    private static JSONObject counts(Map<String, Integer> values) {
        JSONObject object = new JSONObject();
        if (values != null) {
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        }
        return object;
    }

    private static JSONArray paths(List<Path> paths) {
        JSONArray array = new JSONArray();
        for (Path path : paths) array.put(path.toString());
        return array;
    }

    private static JSONArray strings(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static String readable(String message) {
        return message == null || message.isBlank() ? "неизвестная ошибка" : message;
    }

    /** Итог одного запуска. */
    public record Report(boolean ok,
                         Path manifest,
                         List<Path> backups,
                         Map<String, Integer> targetBefore,
                         Map<String, Integer> sourceCounts,
                         Map<String, Integer> targetAfter,
                         Map<String, Integer> verifiedCounts,
                         WriteResult write,
                         List<String> problems,
                         List<String> warnings) {

        public Report {
            backups = backups == null ? List.of() : List.copyOf(backups);
            targetBefore = copy(targetBefore);
            sourceCounts = copy(sourceCounts);
            targetAfter = copy(targetAfter);
            verifiedCounts = copy(verifiedCounts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        private static Map<String, Integer> copy(Map<String, Integer> values) {
            return values == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(values));
        }
    }
}
