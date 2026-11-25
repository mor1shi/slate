package org.example.canvasproject.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Консольный перенос данных Slate.
 *
 * <pre>
 *   MigrationCli mongo sqlite [--dir &lt;каталог SQLite&gt;]
 *   MigrationCli sqlite mongo [--dir &lt;каталог SQLite&gt;]
 * </pre>
 *
 * <p>Mongo берётся из MONGO_URI/MONGO_DB, SQLite — из каталога (по умолчанию
 * XDG_DATA_HOME/canvasproject). Перед записью создаётся резервная копия и
 * манифест; при расхождении счётчиков код выхода — 1.
 */
public final class MigrationCli {

    private static final String MONGO = "mongo";
    private static final String SQLITE = "sqlite";
    private static final List<String> COUNT_ORDER =
            List.of("boards", "elements", "invites", "devices");
    private static final Map<String, String> COUNT_LABELS = Map.of(
            "boards", "доски",
            "elements", "элементы",
            "invites", "приглашения",
            "devices", "устройства");

    private MigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        List<String> positional = new ArrayList<>();
        Path directory = null;
        Path database = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    printUsage();
                    return 0;
                }
                case "-d", "--dir" -> {
                    if (++i >= args.length) return usageError("после " + arg + " нужен каталог");
                    directory = Path.of(args[i]);
                }
                case "--db" -> {
                    if (++i >= args.length) return usageError("после --db нужен файл SQLite");
                    database = Path.of(args[i]);
                }
                default -> {
                    if (arg.startsWith("-")) return usageError("неизвестный параметр " + arg);
                    positional.add(arg.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }

        if (positional.size() != 2) {
            return usageError("нужны источник и назначение");
        }
        String from = positional.get(0);
        String to = positional.get(1);
        if (!MONGO.equals(from) && !SQLITE.equals(from)) {
            return usageError("неизвестный источник: " + from);
        }
        if (!MONGO.equals(to) && !SQLITE.equals(to)) {
            return usageError("неизвестное назначение: " + to);
        }
        if (from.equals(to)) {
            return usageError("источник и назначение совпадают");
        }

        Path baseDirectory = directory != null ? directory : defaultDataDirectory();
        Path sqliteFile = database != null ? database : baseDirectory.resolve("slate.db");
        Path workDirectory = sqliteFile.toAbsolutePath().getParent();
        if (workDirectory == null) workDirectory = baseDirectory.toAbsolutePath();

        String mongoUri = envOrDefault("MONGO_URI", "mongodb://localhost:27017");
        String mongoDb = envOrDefault("MONGO_DB", "canvas_collab");

        MigrationStore source = null;
        MigrationStore target = null;
        try {
            source = MONGO.equals(from)
                    ? new MongoMigrationStore(mongoUri, mongoDb)
                    : new SqliteMigrationStore(sqliteFile);
            target = MONGO.equals(to)
                    ? new MongoMigrationStore(mongoUri, mongoDb)
                    : new SqliteMigrationStore(sqliteFile);

            System.out.println("Источник:  " + source.description());
            System.out.println("Назначение: " + target.description());

            MigrationRunner.Report report =
                    new MigrationRunner(source, target, workDirectory).run();
            printReport(report);
            return report.ok() ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("Ошибка переноса: " + e.getMessage());
            return 1;
        } finally {
            if (target != null) target.close();
            if (source != null) source.close();
        }
    }

    private static void printReport(MigrationRunner.Report report) {
        System.out.println();
        System.out.println("Счётчики (источник → назначение):");
        for (String key : COUNT_ORDER) {
            System.out.println("  " + COUNT_LABELS.get(key) + ": "
                    + report.sourceCounts().getOrDefault(key, 0) + " → "
                    + report.targetAfter().getOrDefault(key, 0));
        }
        for (Path backup : report.backups()) {
            System.out.println("Резервная копия: " + backup);
        }
        System.out.println("Манифест: " + report.manifest());
        for (String warning : report.warnings()) {
            System.out.println("Предупреждение: " + warning);
        }
        if (report.ok()) {
            System.out.println("Перенос завершён, все записи сверены.");
            return;
        }
        System.out.println("Обнаружены расхождения: " + report.problems().size());
        int shown = Math.min(20, report.problems().size());
        for (int i = 0; i < shown; i++) {
            System.out.println("  - " + report.problems().get(i));
        }
        if (report.problems().size() > shown) {
            System.out.println("  … остальные — в манифесте");
        }
    }

    private static int usageError(String message) {
        System.err.println("Ошибка аргументов: " + message);
        System.err.println();
        printUsage();
        return 2;
    }

    private static void printUsage() {
        System.out.println("""
                Перенос данных Slate между SQLite и MongoDB.

                Использование:
                  MigrationCli <источник> <назначение> [--dir <каталог>] [--db <файл>]

                Источник и назначение: mongo или sqlite (должны различаться).

                Параметры:
                  --dir <каталог>  каталог локальных данных SQLite
                                   (по умолчанию XDG_DATA_HOME/canvasproject)
                  --db  <файл>     файл SQLite (по умолчанию <каталог>/slate.db)

                Переменные окружения:
                  MONGO_URI  по умолчанию mongodb://localhost:27017
                  MONGO_DB   по умолчанию canvas_collab

                Перед записью создаётся резервная копия в <каталог>/backups и
                манифест <каталог>/migration-manifest.json. Повторный запуск
                идемпотентен: существующие записи обновляются, дублей нет.
                """);
    }

    static Path defaultDataDirectory() {
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "canvasproject");
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", "canvasproject");
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
