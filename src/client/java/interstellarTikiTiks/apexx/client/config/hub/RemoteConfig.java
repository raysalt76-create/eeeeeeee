package interstellarTikiTiks.apexx.client.config.hub;

public class RemoteConfig {
    public String id;
    public String name;
    public String author;
    public String description;
    public String gameMode = "Other";
    public String fileName;
    public long downloads;
    public long updatedAt;
    /** True when this config is public (listed in the hub); false = private to the owner. */
    public boolean isPublic = true;
    /** True when the signed-in user owns this config and may delete it or change its visibility. */
    public boolean owned = false;
    /** Filter tags (Legit, Blatant, Crystal, Mace, SpearMace, Sword, SMP). */
    public java.util.List<String> tags = new java.util.ArrayList<>();
    /** Community rating: average stars (0 when unrated) and how many players rated it. */
    public double ratingAvg;
    public int ratingCount;
    /** Imports + 2x ratings over the last 7 days (the "Popular this week" score). */
    public int weeklyScore;
    /** The signed-in player's own stars for it, 0 when they have not rated it. */
    public int myRating;

    public String getDisplayName() {
        return name + " by " + author;
    }
}
