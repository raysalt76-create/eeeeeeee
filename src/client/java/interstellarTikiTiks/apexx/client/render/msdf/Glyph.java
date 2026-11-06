package interstellarTikiTiks.apexx.client.render.msdf;

import com.google.gson.JsonObject;

/**
 * One glyph from an msdf-atlas-gen JSON.
 *
 * <p>Two coordinate spaces, and mixing them up is the classic way to get garbled text:
 * <ul>
 *   <li><b>plane</b> bounds are em-relative layout coordinates — where the quad sits relative to
 *       the pen position, scaled by the requested font size.</li>
 *   <li><b>atlas</b> bounds are pixel coordinates INTO the atlas texture, and the generator writes
 *       them with {@code yOrigin: "bottom"}, so V has to be flipped when building UVs.</li>
 * </ul>
 *
 * <p>A glyph with no bounds at all (space) still carries a valid {@link #getAdvance()} and simply
 * moves the pen without emitting geometry.
 */
public final class Glyph {

    private int unicode;
    private float advance;

    private float planeLeft, planeBottom, planeRight, planeTop;
    private float atlasLeft, atlasBottom, atlasRight, atlasTop;

    private Glyph() {
    }

    public static Glyph parse(JsonObject object) {
        Glyph glyph = new Glyph();
        glyph.unicode = object.get("unicode").getAsInt();
        glyph.advance = object.get("advance").getAsFloat();

        if (object.has("planeBounds")) {
            JsonObject p = object.getAsJsonObject("planeBounds");
            glyph.planeLeft = p.get("left").getAsFloat();
            glyph.planeBottom = p.get("bottom").getAsFloat();
            glyph.planeRight = p.get("right").getAsFloat();
            glyph.planeTop = p.get("top").getAsFloat();
        }

        if (object.has("atlasBounds")) {
            JsonObject a = object.getAsJsonObject("atlasBounds");
            glyph.atlasLeft = a.get("left").getAsFloat();
            glyph.atlasBottom = a.get("bottom").getAsFloat();
            glyph.atlasRight = a.get("right").getAsFloat();
            glyph.atlasTop = a.get("top").getAsFloat();
        }
        return glyph;
    }

    /** True when this glyph has actual geometry to draw (a space does not). */
    public boolean hasGeometry() {
        return planeRight - planeLeft != 0.0f;
    }

    public int getUnicode()      { return unicode; }
    public float getAdvance()    { return advance; }

    public float getPlaneLeft()   { return planeLeft; }
    public float getPlaneBottom() { return planeBottom; }
    public float getPlaneRight()  { return planeRight; }
    public float getPlaneTop()    { return planeTop; }

    public float getAtlasLeft()   { return atlasLeft; }
    public float getAtlasBottom() { return atlasBottom; }
    public float getAtlasRight()  { return atlasRight; }
    public float getAtlasTop()    { return atlasTop; }
}
