package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.SchemaSnapshot;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public class SchemaCache {

    private static final Logger log = LoggerFactory.getLogger(SchemaCache.class);

    private final Path cacheDir;
    private final Duration ttl;
    private final Gson gson;

    public SchemaCache(Path cacheDir, Duration ttl) {
        this.cacheDir = cacheDir;
        this.ttl = ttl;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantAdapter())
                .create();
    }

    public SchemaSnapshot load(ConnectionConfig config) {
        Path file = cacheFile(config);
        if (!Files.exists(file)) {
            log.debug("No cache file: {}", file);
            return null;
        }

        try {
            String json = Files.readString(file);
            SchemaSnapshot snapshot = gson.fromJson(json, SchemaSnapshot.class);
            if (snapshot == null || snapshot.extractedAt() == null) return null;

            Duration age = Duration.between(snapshot.extractedAt(), Instant.now());
            if (age.compareTo(ttl) > 0) {
                log.info("Cache expired (age={}s, ttl={}s): {}", age.toSeconds(), ttl.toSeconds(), file);
                return null;
            }

            log.info("Using cached snapshot (age={}s): {}", age.toSeconds(), config.label());
            return snapshot;
        } catch (Exception e) {
            log.warn("Failed to read cache: {} error={}", file, e.getMessage());
            return null;
        }
    }

    public void save(ConnectionConfig config, SchemaSnapshot snapshot) {
        Path file = cacheFile(config);
        try {
            Files.createDirectories(cacheDir);
            String json = gson.toJson(snapshot);
            Files.writeString(file, json);
            log.info("Cached snapshot: {}", file);
        } catch (IOException e) {
            log.warn("Failed to write cache: {} error={}", file, e.getMessage());
        }
    }

    private Path cacheFile(ConnectionConfig config) {
        String name = config.server().replace('.', '-') + "_" + config.port() + "_" + config.database() + ".json";
        return cacheDir.resolve(name);
    }

    private static class InstantAdapter implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Instant.parse(json.getAsString());
        }
    }
}
