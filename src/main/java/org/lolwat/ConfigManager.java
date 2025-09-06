package org.lolwat;

import com.google.gson.*;
import lombok.Getter;
import lombok.Setter;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.grandexchange.LivePrices;
import org.dreambot.api.methods.world.Worlds;
import org.dreambot.api.script.ScriptManager;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.wrappers.items.Item;
import javax.annotation.Nullable;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.List;

public class ConfigManager {
    @Getter
    @Setter
    private static ConfigManager instance;
    @Getter
    @Setter
    private boolean hasLoaded;
    @Getter
    @Setter
    private boolean firstStart;
    @Setter
    private boolean muleConnectionFailed;

    private final HashMap<Object, Object> config;


    public ConfigManager() {
        config = new HashMap<>();
        hasLoaded = false;
        firstStart = true;
        muleConnectionFailed = false;
    }

    private JsonObject getDefaultProfile() {
        JsonObject defaultProfile = new JsonObject();
        defaultProfile.addProperty("listen_port", 8081);
        return defaultProfile;
    }

    public void loadFromProfile(String p) {
        String filePath = System.getProperty("scripts.path") + "/WatScripts/Mule/" + p + ".json";

        try {
            Gson gson = new Gson();

            File file = new File(filePath);
            if (!file.exists()) {
                file.getParentFile().mkdirs();

                JsonObject defaultProfile = getDefaultProfile();
                FileWriter fileWriter = new FileWriter(file);
                gson.toJson(defaultProfile, fileWriter);
                fileWriter.close();
            }

            JsonObject jsonObject = gson.fromJson(new FileReader(filePath), JsonObject.class);
            JsonObject defaultProfile = getDefaultProfile();

            for (String key : defaultProfile.keySet()) {
                if (!jsonObject.has(key)) {
                    jsonObject.add(key, defaultProfile.get(key));
                }
            }

            FileWriter fileWriter = new FileWriter(file);
            gson.toJson(jsonObject, fileWriter);
            fileWriter.close();

            for (String key : jsonObject.keySet()) {
                config.put(key, jsonObject.get(key));
            }

            config.put("hitpoints", 100);
            hasLoaded = true;

        } catch (IOException | JsonSyntaxException ignored) {
            Logger.error("Encountered an error during setup");
        }
    }

    public String getConfigString(String key) {
        String value = config.get(key).toString();
        return value.replace("\"", "");
    }

    public boolean getConfigBoolean(String key) {
        String value = config.get(key).toString().replace("\"", "");
        return Objects.equals(value, "true") || Objects.equals(value, "1");
    }

    public int getConfigInt(String key) {
        return Integer.parseInt(config.get(key).toString());
    }

    public double getConfigDouble(String key) {
        return Double.parseDouble(config.get(key).toString());
    }

    public boolean hasMuleConnectionFailed() {
        return muleConnectionFailed;
    }

}
