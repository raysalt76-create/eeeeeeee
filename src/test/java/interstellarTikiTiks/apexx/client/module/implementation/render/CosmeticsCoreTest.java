package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.cosmetics.CapeDesign;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Cape image encoding size caps and decoding safety, cape texture layout, cloth physics stability. */
class CosmeticsCoreTest {

    private static int[] noise(int w, int h, long seed) {
        Random r = new Random(seed);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | r.nextInt(0x1000000);
        return px;
    }

    @Test
    void encodedImagesAlwaysFitTheCapAndRoundTrip() {
        for (int[] size : new int[][]{{1, 1}, {64, 32}, {640, 480}, {300, 1200}, {2000, 2000}}) {
            int[] src = noise(size[0], size[1], size[0] * 31L + size[1]);
            String enc = Cosmetics.CapeImageCodec.encode(src, size[0], size[1]);
            assertNotNull(enc, "encodes " + size[0] + "x" + size[1]);
            assertTrue(enc.length() <= CapeDesign.MAX_IMAGE_CHARS, "within cap: " + enc.length());
            assertTrue(CapeDesign.isImageWellFormed(enc));
            Cosmetics.CapeImageCodec.Image img = Cosmetics.CapeImageCodec.decode(enc);
            assertNotNull(img);
            assertTrue(img.width() <= 64 && img.height() <= 64);
            assertEquals(img.width() * img.height(), img.argb().length);
            // Kept at the cape face's 5:8 aspect.
            assertEquals(5.0 / 8.0, img.width() / (double) img.height(), 1e-9);
        }
    }

    @Test
    void flatImagesKeepTheirColour() {
        int[] src = new int[100 * 160];
        java.util.Arrays.fill(src, 0xFF3A6BD8);
        Cosmetics.CapeImageCodec.Image img = Cosmetics.CapeImageCodec.decode(Cosmetics.CapeImageCodec.encode(src, 100, 160));
        assertNotNull(img);
        for (int p : img.argb()) assertEquals(0xFF3A6BD8, p);
    }

    @Test
    void malformedOrHostileDataIsRejected() {
        assertNull(Cosmetics.CapeImageCodec.decode(null));
        assertNull(Cosmetics.CapeImageCodec.decode(""));
        assertNull(Cosmetics.CapeImageCodec.decode("not+base64/"));
        assertNull(Cosmetics.CapeImageCodec.decode("A".repeat(CapeDesign.MAX_IMAGE_CHARS + 1)));
        assertNull(Cosmetics.CapeImageCodec.decode("AAAA"));
        // A valid deflate stream of a header claiming 200x200 pixels.
        byte[] header = {1, (byte) 200, (byte) 200, 2, 0, 0, 0, 1, 1, 1};
        java.util.zip.Deflater d = new java.util.zip.Deflater(9, true);
        d.setInput(header);
        d.finish();
        byte[] buf = new byte[64];
        int n = d.deflate(buf);
        d.end();
        String bogus = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(buf, n));
        assertNull(Cosmetics.CapeImageCodec.decode(bogus));
        // An inflation bomb (lots of zeros) is cut off.
        byte[] zeros = new byte[200_000];
        d = new java.util.zip.Deflater(9, true);
        d.setInput(zeros);
        d.finish();
        byte[] big = new byte[4096];
        n = d.deflate(big);
        d.end();
        String bomb = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(big, n));
        if (bomb.length() <= CapeDesign.MAX_IMAGE_CHARS) assertNull(Cosmetics.CapeImageCodec.decode(bomb));
    }

    @Test
    void capeDesignRejectsOversizedOrForeignImageText() {
        CapeDesign d = CapeDesign.defaultCustom().withImage("x".repeat(CapeDesign.MAX_IMAGE_CHARS + 1));
        assertNull(d.image());
        assertNull(CapeDesign.defaultCustom().withImage("ab+c").image());
        assertEquals("AB12", CapeDesign.defaultCustom().withText("ab-12xyz").text());
    }

    @Test
    void capeArtFillsTheWholeTextureForEveryPreset() {
        for (CapeDesign.Preset p : CapeDesign.PRESETS) {
            int[] px = Cosmetics.CapeArt.render(p.design().withText("AX"));
            assertEquals(Cosmetics.CapeArt.WIDTH * Cosmetics.CapeArt.HEIGHT, px.length);
            int outer = px[(Cosmetics.CapeArt.SCALE * 5) * Cosmetics.CapeArt.WIDTH + Cosmetics.CapeArt.SCALE * 3];
            assertEquals(0xFF, outer >>> 24, p.id() + " outer face is opaque");
        }
        String enc = Cosmetics.CapeImageCodec.encode(noise(50, 80, 3), 50, 80);
        assertEquals(Cosmetics.CapeArt.WIDTH * Cosmetics.CapeArt.HEIGHT, Cosmetics.CapeArt.render(CapeDesign.defaultCustom().withImage(enc)).length);
    }

    @Test
    void capePhysicsStaysFiniteAndBoundedUnderAnyInput() {
        Cosmetics.CapeSim sim = new Cosmetics.CapeSim();
        Random r = new Random(42);
        float[] pitch = new float[Cosmetics.CapeSim.SEGMENTS], roll = new float[Cosmetics.CapeSim.SEGMENTS];
        float[] weird = {Float.NaN, Float.POSITIVE_INFINITY, -1e9f, 1e9f, 0f};
        for (int i = 0; i < 20_000; i++) {
            float dt = i % 97 == 0 ? 5f : i % 89 == 0 ? Float.NaN : r.nextFloat() * 0.05f;
            float f = i % 53 == 0 ? weird[i % weird.length] : (r.nextFloat() - 0.3f) * 1.5f;
            sim.step(dt, new Cosmetics.CapeSim.Motion(f, (r.nextFloat() - 0.5f) * 2f, (r.nextFloat() - 0.6f) * 4f,
                    (r.nextFloat() - 0.5f) * 3000f, r.nextBoolean()));
            sim.read(pitch, roll);
            for (int s = 0; s < pitch.length; s++) {
                assertTrue(Float.isFinite(pitch[s]) && Float.isFinite(roll[s]));
                assertTrue(pitch[s] >= Math.toRadians(-4) - 1e-4 && pitch[s] <= Math.toRadians(115) + 1e-4);
                assertTrue(Math.abs(roll[s]) <= Math.toRadians(35) + 1e-4);
            }
        }
    }

    @Test
    void capeSettlesWhenStandingStill() {
        Cosmetics.CapeSim sim = new Cosmetics.CapeSim();
        sim.step(0.2f, new Cosmetics.CapeSim.Motion(0.6f, 0.3f, -1.5f, 900f, false));
        for (int i = 0; i < 600; i++) sim.step(1f / 60f, Cosmetics.CapeSim.Motion.REST);
        float[] pitch = new float[Cosmetics.CapeSim.SEGMENTS], roll = new float[Cosmetics.CapeSim.SEGMENTS];
        sim.read(pitch, roll);
        // Back near the resting lean (a few degrees, with a little wind ripple), hanging straight sideways.
        assertEquals(Math.toRadians(6), pitch[0], Math.toRadians(3));
        for (float r : roll) assertEquals(0, r, Math.toRadians(4));
        for (float p : pitch) assertTrue(p < Math.toRadians(25));
    }
}
