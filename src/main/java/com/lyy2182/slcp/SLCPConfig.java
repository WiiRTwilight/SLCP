package com.lyy2182.slcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SLCPConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("slcp");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILENAME = "config.json";
    private static final String DEFAULT_CONFIG_RESOURCE = "/slcp_config.json";

    private final List<Entry> entries;
    private final Path configFile;

    private SLCPConfig(List<Entry> entries, Path configFile) {
        this.entries = Collections.unmodifiableList(entries);
        this.configFile = configFile;
    }

    public record Entry(String name, String url, String output) {}

    public List<Entry> getEntries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public static SLCPConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("slcp");
        Path configFile = configDir.resolve(CONFIG_FILENAME);

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory: {}", configDir, e);
            return emptyConfig(configFile);
        }

        if (!Files.exists(configFile)) {
            LOGGER.info("Config file not found, releasing default to {}", configFile);
            releaseDefaultConfig(configFile);
        }

        List<Entry> entries = parseConfig(configFile);
        LOGGER.info("Loaded {} entries from config:", entries.size());
        for (Entry entry : entries) {
            LOGGER.info("  [{}] {} -> {}", entry.name(), entry.url(), entry.output());
        }
        return new SLCPConfig(entries, configFile);
    }

    public void save() {
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject entryObj = new JsonObject();
            JsonObject data = new JsonObject();
            data.addProperty("url", entry.url());
            data.addProperty("output", entry.output());
            entryObj.add(entry.name(), data);
            array.add(entryObj);
        }
        try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            GSON.toJson(array, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", configFile, e);
        }
    }

    private static void releaseDefaultConfig(Path destination) {
        try (InputStream in = SLCPConfig.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Default config resource not found in JAR: {}", DEFAULT_CONFIG_RESOURCE);
                try {
                    Files.createFile(destination);
                } catch (IOException e) {
                    LOGGER.error("Failed to create empty config file", e);
                }
                return;
            }
            Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Released default config to {}", destination);
        } catch (IOException e) {
            LOGGER.error("Failed to release default config to {}", destination, e);
        }
    }

    private static List<Entry> parseConfig(Path configFile) {
        List<Entry> entries = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonArray array = GSON.fromJson(reader, JsonArray.class);
            if (array == null) {
                LOGGER.warn("Config is not a JSON array, returning empty");
                return entries;
            }
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    LOGGER.warn("Skipping non-object entry in config: {}", element);
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                for (Map.Entry<String, JsonElement> nameEntry : obj.entrySet()) {
                    String name = nameEntry.getKey();
                    JsonElement value = nameEntry.getValue();
                    if (!value.isJsonObject()) {
                        LOGGER.warn("Skipping entry '{}': value is not an object", name);
                        continue;
                    }
                    JsonObject data = value.getAsJsonObject();
                    JsonElement urlElem = data.get("url");
                    JsonElement outputElem = data.get("output");
                    if (urlElem == null || !urlElem.isJsonPrimitive() || !urlElem.getAsJsonPrimitive().isString()) {
                        LOGGER.warn("Skipping entry '{}': missing or invalid 'url' field", name);
                        continue;
                    }
                    if (outputElem == null || !outputElem.isJsonPrimitive() || !outputElem.getAsJsonPrimitive().isString()) {
                        LOGGER.warn("Skipping entry '{}': missing or invalid 'output' field", name);
                        continue;
                    }
                    String url = urlElem.getAsString();
                    String output = outputElem.getAsString();
                    entries.add(new Entry(name, url, output));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read config file: {}", configFile, e);
        } catch (Exception e) {
            LOGGER.error("Failed to parse config file: {}", configFile, e);
        }
        return entries;
    }

    private static SLCPConfig emptyConfig(Path configFile) {
        return new SLCPConfig(Collections.emptyList(), configFile);
    }
}
