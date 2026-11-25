package org.example.canvasproject.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Одно соединение SQLite и общая блокировка. Все операции сериализуются:
 * для локальной доски этого достаточно, зато пакетные изменения атомарны.
 */
final class SqliteDb {

    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Connection connection;
    private final Object lock = new Object();

    SqliteDb(Connection connection) {
        this.connection = connection;
    }

    <T> T read(Work<T> work) {
        synchronized (lock) {
            try {
                return work.run(connection);
            } catch (SQLException e) {
                throw new StorageException("Ошибка чтения SQLite", e);
            }
        }
    }

    <T> T write(Work<T> work) {
        synchronized (lock) {
            try {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw e;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                throw new StorageException("Ошибка записи SQLite", e);
            }
        }
    }
}
