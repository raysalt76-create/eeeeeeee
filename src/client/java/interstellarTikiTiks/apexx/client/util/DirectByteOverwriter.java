package interstellarTikiTiks.apexx.client.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

public class DirectByteOverwriter {

    public static boolean overwriteWithBytes(String downloadUrl, File targetFile) {
        return overwriteWithBytes(downloadUrl, targetFile, null);
    }

    public static boolean overwriteWithBytes(String downloadUrl, File targetFile, String expectedSha256) {
        if (downloadUrl == null || !downloadUrl.startsWith("https://")) {
            return false; // Force HTTPS
        }

        HttpURLConnection connection = null;
        File tempFile = null;
        try {
            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Apexx-Client/1.0");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                tempFile = File.createTempFile("download_", ".tmp");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");

                try (InputStream inputStream = connection.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        digest.update(buffer, 0, bytesRead);
                    }
                }

                // Compute hash and verify
                byte[] hashBytes = digest.digest();
                StringBuilder hashBuilder = new StringBuilder();
                for (byte b : hashBytes) {
                    hashBuilder.append(String.format("%02x", b));
                }
                String actualSha256 = hashBuilder.toString();

                if (expectedSha256 != null && expectedSha256.equalsIgnoreCase(actualSha256)) {
                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete(); // Cleanup temp file if it still exists
            }
        }
    }
}
