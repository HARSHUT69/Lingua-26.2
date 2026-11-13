package dev.lingua;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class LinguaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("lingua.json");
    private static LinguaConfig instance = new LinguaConfig();

    public String apiKey = "";
    public String model = "claude-haiku-5-5";
    public boolean incoming = true;   // dusre players ka chat -> Hinglish
    public boolean outgoing = true;   // tumhara chat -> target language
    public String targetLanguage = "Japanese";

    public static LinguaConfig get() { return instance; }

    /** Config me key ho to wo, warna ANTHROPIC_API_KEY environment variable. */
    public String key() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null ? "" : env.trim();
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                LinguaConfig c = GSON.fromJson(Files.readString(FILE), LinguaConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception e) {
            LinguaClient.LOGGER.warn("Lingua config load fail", e);
        }
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (Exception e) {
            LinguaClient.LOGGER.warn("Lingua config save fail", e);
        }
    }
}
