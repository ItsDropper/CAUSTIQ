package dev.itsdropper.caustiq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Persistent, bounded local settings for Caustiq. */
public final class CaustiqConfig {
    public static final int MIN_COMPILE_BUDGET = 1;
    public static final int MAX_COMPILE_BUDGET = 8;
    private static final Path FILE = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("caustiq.properties");
    private static final CaustiqConfig INSTANCE = load();

    private boolean enabled;
    private int compileBudget = 1;
    private String selectedPackId = "";
    private String selectedOptionName = "";
    private final Map<String, String> options = new LinkedHashMap<>();

    private CaustiqConfig() { }
    public static CaustiqConfig get() { return INSTANCE; }

    public synchronized boolean enabled() { return enabled; }
    public synchronized int compileBudget() { return compileBudget; }
    public synchronized String selectedPackId() { return selectedPackId; }
    public synchronized String selectedOptionName() { return selectedOptionName; }

    public synchronized String option(String packId, String name, String fallback) {
        return options.getOrDefault(optionKey(packId, name), fallback);
    }

    public synchronized Map<String, String> optionsForPack(String packId) {
        String prefix = "option." + safeKey(packId) + ".";
        Map<String, String> result = new LinkedHashMap<>();
        options.forEach((key, value) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
        });
        return Map.copyOf(result);
    }

    public synchronized void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public synchronized void setCompileBudget(int value) {
        compileBudget = Math.clamp(value, MIN_COMPILE_BUDGET, MAX_COMPILE_BUDGET);
        save();
    }

    public synchronized void selectPack(String packId) {
        selectedPackId = packId == null ? "" : packId;
        selectedOptionName = "";
        save();
    }

    public synchronized void selectOption(String optionName) {
        selectedOptionName = optionName != null
                && optionName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") ? optionName : "";
        save();
    }

    public synchronized void setOption(String packId, String name, String value) {
        options.put(optionKey(packId, name), value);
        save();
    }

    private static String optionKey(String packId, String name) {
        return "option." + safeKey(packId) + "." + safeKey(name);
    }

    private static String safeKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Caustiq configuration key");
        }
        return value;
    }

    private static CaustiqConfig load() {
        CaustiqConfig config = new CaustiqConfig();
        if (!Files.isRegularFile(FILE)) return config;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(FILE)) {
            properties.load(input);
            config.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
            config.compileBudget = Math.clamp(
                    Integer.parseInt(properties.getProperty("compileBudget", "1")),
                    MIN_COMPILE_BUDGET, MAX_COMPILE_BUDGET);
            config.selectedPackId = properties.getProperty("selectedPack", "");
            if (config.selectedPackId.length() > 256) config.selectedPackId = "";
            config.selectedOptionName = properties.getProperty("selectedOption", "");
            if (!config.selectedOptionName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                config.selectedOptionName = "";
            }
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("option.") && key.length() <= 300) {
                    String value = properties.getProperty(key, "");
                    if (value.length() <= 256) config.options.put(key, value);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            config.enabled = false;
            config.compileBudget = 1;
            config.selectedPackId = "";
            config.selectedOptionName = "";
            config.options.clear();
        }
        return config;
    }

    private synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("compileBudget", Integer.toString(compileBudget));
        properties.setProperty("selectedPack", selectedPackId);
        properties.setProperty("selectedOption", selectedOptionName);
        new TreeMap<>(options).forEach(properties::setProperty);
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Caustiq client settings");
            }
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            CaustiqClient.LOGGER.error("Unable to save Caustiq settings to {}", FILE, exception);
        }
    }
}
