package com.fluxlogic.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads/saves {@code config/fluxlogic.json} and holds the live config.
 *
 * <p>Hot-reload friendly: the active config is held behind an
 * {@link AtomicReference} so the settings screen can swap in a freshly edited
 * instance without the tick thread ever seeing a half-written object.
 */
public final class ConfigManager {

    public static final Logger LOG = LoggerFactory.getLogger("FluxLogic");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final AtomicReference<FluxConfig> ACTIVE = new AtomicReference<>(new FluxConfig());
    private static volatile boolean loaded;

    private ConfigManager() {}

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("fluxlogic.json");
    }

    /** Read config from disk, writing defaults if the file is missing/corrupt. */
    public static synchronized void load() {
        loaded = true;
        Path path = configPath();
        if (Files.notExists(path)) {
            ACTIVE.set(new FluxConfig());
            save();
            LOG.info("[FluxLogic] Wrote default config to {}", path);
            return;
        }
        try {
            String json = Files.readString(path);
            FluxConfig parsed = GSON.fromJson(json, FluxConfig.class);
            if (parsed == null) {
                throw new IOException("config parsed to null");
            }
            int versionBefore = parsed.configVersion;
            ACTIVE.set(migrate(parsed));
            if (parsed.configVersion != versionBefore) {
                save(); // persist the migration so it runs exactly once
            }
            LOG.info("[FluxLogic] Loaded config from {}", path);
        } catch (Exception e) {
            // Never let a bad config brick the client — back it up and reset.
            LOG.warn("[FluxLogic] Failed to read config ({}); regenerating defaults", e.toString());
            backupCorrupt(path);
            ACTIVE.set(new FluxConfig());
            save();
        }
    }

    /** Persist the active config to disk (pretty-printed JSON). */
    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(ACTIVE.get()));
        } catch (IOException e) {
            LOG.error("[FluxLogic] Could not save config to {}", path, e);
        }
    }

    /**
     * The live, read-mostly config. Safe to call every tick.
     *
     * <p>Lazy-loads on first use: some hooks (e.g. the narrator-init redirect)
     * can fire during {@code Minecraft}'s constructor, possibly before the mod
     * initializer has run — they must still see the user's saved settings.
     */
    public static FluxConfig get() {
        if (!loaded) {
            load();
        }
        return ACTIVE.get();
    }

    /** Swap in an edited config (called by the settings screen on apply). */
    public static void replace(FluxConfig edited) {
        ACTIVE.set(edited);
        save();
    }

    // -------------------------------------------------------------- internals

    private static FluxConfig migrate(FluxConfig cfg) {
        // v1/v2 -> v3: FluxLogic was slimmed down to the mouse de-stutter fix
        // only. Old sections (camera/presets/tactical/...) are simply ignored
        // on read; bumping the version re-saves the file in the new shape.
        if (cfg.configVersion < 3) {
            cfg.configVersion = 3;
            LOG.info("[FluxLogic] Config migrated to v3 (stutter-fix-only schema).");
        }
        // A section explicitly set to null in JSON deserialises as null —
        // heal that to defaults. (Also covers sections added after the user's
        // file was first written.)
        if (cfg.input == null) {
            cfg.input = new FluxConfig.Input();
        }
        if (cfg.workarounds == null) {
            cfg.workarounds = new FluxConfig.Workarounds();
        }
        return cfg;
    }

    private static void backupCorrupt(Path path) {
        try {
            Path bak = path.resolveSibling("fluxlogic.json.bak");
            Files.deleteIfExists(bak);
            Files.move(path, bak);
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
