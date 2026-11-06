package interstellarTikiTiks.apexx.client.friend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.util.GameProfileCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class UUIDFetcher {
    private static final String MOJANG_API_URL = "https://api.mojang.com/users/profiles/minecraft/";

    public static CompletableFuture<UUID> fetchUUID(String username) {
        return CompletableFuture.supplyAsync(() -> {

            UUID inGameUUID = getUUIDFromGame(username);
            if (inGameUUID != null) {
                return inGameUUID;
            }

            return fetchUUIDFromMojang(username);
        });
    }

    private static UUID getUUIDFromGame(String username) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.getNetworkHandler() == null) {
            return null;
        }

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = GameProfileCompat.getName(entry.getProfile());
            if (name != null && name.equalsIgnoreCase(username)) {
                return GameProfileCompat.getId(entry.getProfile());
            }
        }

        return null;
    }

    private static UUID fetchUUIDFromMojang(String username) {
        try {
            URL url = new URL(MOJANG_API_URL + username);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                String uuidString = json.get("id").getAsString();

                String formattedUUID = uuidString.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5"
                );

                return UUID.fromString(formattedUUID);
            } else if (responseCode == 204 || responseCode == 404) {
                return null;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static UUID fetchUUIDSync(String username) {
        try {
            return fetchUUID(username).get();
        } catch (Exception e) {
            return null;
        }
    }
}
