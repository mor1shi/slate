package org.example.canvasproject.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Файловое хранилище ассетов с адресацией по SHA-256 и дедупликацией.
 * Один и тот же файл, вставленный дважды, занимает место один раз.
 */
public final class FileAssetStore implements AssetStore {

    public static final long DEFAULT_MAX_BYTES = 16L * 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path root;
    private final long maxBytes;

    public FileAssetStore(Path root) {
        this(root, DEFAULT_MAX_BYTES);
    }

    public FileAssetStore(Path root, long maxBytes) {
        this.root = root.toAbsolutePath();
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("Не удалось создать каталог ассетов: " + root, e);
        }
    }

    @Override
    public String put(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Нет данных ассета");
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("Ассет больше лимита: " + bytes.length);
        }
        String hash = hash(bytes);
        Path target = pathOf(hash);
        if (Files.exists(target)) return hash;
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".asset-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return hash;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить ассет", e);
        }
    }

    @Override
    public Optional<byte[]> get(String hash) {
        Path file = pathOf(hash);
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать ассет", e);
        }
    }

    @Override
    public boolean exists(String hash) {
        Path file = pathOf(hash);
        return file != null && Files.isRegularFile(file);
    }

    @Override
    public long size(String hash) {
        Path file = pathOf(hash);
        if (file == null) return -1;
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public boolean delete(String hash) {
        Path file = pathOf(hash);
        if (file == null) return false;
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось удалить ассет", e);
        }
    }

    /** null для невалидного хэша — защита от выхода за пределы каталога. */
    private Path pathOf(String hash) {
        if (hash == null || !HASH.matcher(hash).matches()) return null;
        return root.resolve(hash.substring(0, 2)).resolve(hash);
    }

    public static String hash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
