package interstellarTikiTiks.apexx.client.config.hub;

public class ConfigHubProfile {
    public boolean authenticated;
    public boolean premium;
    public boolean uploadAllowed;
    public boolean hasClaimedName;
    public boolean profileLocked;
    public boolean webUiOnly;
    public String claimedName;
    public String licenseTag;

    public static ConfigHubProfile unknown() {
        ConfigHubProfile profile = new ConfigHubProfile();
        profile.authenticated = false;
        profile.premium = false;
        profile.uploadAllowed = false;
        profile.hasClaimedName = false;
        profile.profileLocked = false;
        profile.webUiOnly = false;
        profile.claimedName = null;
        profile.licenseTag = "";
        return profile;
    }
}
