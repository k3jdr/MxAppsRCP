package com.k3jdr.mx.core.json;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** JSON as the macOS apps write it: pretty-printed, keys in declaration order, no HTML escaping. */
public final class Json {
    private Json() {}

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    public static String write(Object value) { return GSON.toJson(value); }

    public static <T> T read(String json, Class<T> type) { return GSON.fromJson(json, type); }
    public static <T> T read(String json, Type type) { return GSON.fromJson(json, type); }

    public static JsonElement parse(String json) { return JsonParser.parseString(json); }

    public static <T> T readFile(Path file, Class<T> type) throws IOException {
        return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
    }

    /** Writes through a temporary file, so a crash never leaves half a document. */
    public static void writeFile(Path file, Object value) throws IOException {
        writeText(file, value instanceof JsonElement e ? GSON.toJson(e) : GSON.toJson(value));
    }

    public static void writeText(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".mx", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
