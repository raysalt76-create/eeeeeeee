package interstellarTikiTiks.apexx.client.gui.preview;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU re-implementation of the GPU UI renderer, used only by the offline GUI preview tool.
 *
 * <p>Each draw reproduces its shader's math per framebuffer pixel: the rounded-rect signed distance
 * with fwidth antialiasing (gpuui_rect), border rings, shadow falloff (gpuui_shadow), MSDF text from
 * the real atlas (gpuui_text), images (gpuui_image) and rounded clip masks (gpuui_clip_mask), with
 * the same integer snapping GpuUiRenderer applies before submission. Geometry, text and alignment
 * therefore match the game. Glass is approximated from the same lens maths over a box-blurred
 * backdrop, and shader materials (marble, mist, fog) are stood in for by procedural noise.
 */
final class SoftwareUiRasterizer {
    private final int width, height;
    /** Display-list units per framebuffer pixel inverse: lists are authored in framebuffer pixels. */
    private final float scale = 1f;
    /** Pixels per Minecraft GUI unit; scissors (and so clip edges) live on this grid. */
    private final float snapGrid;
    private final float[] r, g, b;
    private final BufferedImage backdrop;
    private final Map<Integer, float[][]> blurredBackdrops = new HashMap<>();
    private final Map<String, BufferedImage> textures = new HashMap<>();
    private final List<String> approximations = new ArrayList<>();

    private record RoundClip(float x, float y, float w, float h, float radius) { }
    private record Clip(int x0, int y0, int x1, int y1, boolean scissored, List<RoundClip> rounds, boolean empty) { }

    SoftwareUiRasterizer(BufferedImage backdrop, float guiScale) {
        this.backdrop = backdrop;
        this.width = backdrop.getWidth();
        this.height = backdrop.getHeight();
        this.snapGrid = Math.max(1f, guiScale);
        int n = width * height;
        r = new float[n]; g = new float[n]; b = new float[n];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = backdrop.getRGB(x, y);
                int i = y * width + x;
                r[i] = ((argb >> 16) & 255) / 255f;
                g[i] = ((argb >> 8) & 255) / 255f;
                b[i] = (argb & 255) / 255f;
            }
        }
    }

    List<String> approximations() { return approximations; }

    BufferedImage image() {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < r.length; i++) {
            out.setRGB(i % width, i / width, (to8(r[i]) << 16) | (to8(g[i]) << 8) | to8(b[i]));
        }
        return out;
    }

    // ------------------------------------------------------------------ display list walk

    void render(UiDisplayList list) {
        ArrayDeque<Clip> stack = new ArrayDeque<>();
        Clip clip = new Clip(0, 0, width, height, false, List.of(), false);
        for (UiDrawCommand command : list.commands()) {
            switch (command) {
                case UiDrawCommand.ClipPush push -> {
                    stack.push(clip);
                    clip = pushClip(clip, push);
                }
                case UiDrawCommand.ClipPop ignored -> clip = stack.pop();
                default -> { if (!clip.empty()) draw(command, clip); }
            }
        }
    }

    private Clip pushClip(Clip parent, UiDrawCommand.ClipPush push) {
        // GpuUiRenderer.snapClip: pixel-space clips are aligned to the GUI-unit grid so the scissor
        // and the clip rectangle coincide; the scissor is then intersected with the parent's.
        float k = snapGrid;
        float sx = Math.round(push.x() / k) * k, sy = Math.round(push.y() / k) * k;
        float ex = Math.round((push.x() + push.width()) / k) * k, ey = Math.round((push.y() + push.height()) / k) * k;
        push = new UiDrawCommand.ClipPush(sx, sy, Math.max(0f, ex - sx), Math.max(0f, ey - sy), push.radius());
        int x0 = Math.max(parent.x0(), Math.round(sx));
        int y0 = Math.max(parent.y0(), Math.round(sy));
        int x1 = Math.min(parent.x1(), Math.round(ex));
        int y1 = Math.min(parent.y1(), Math.round(ey));
        boolean empty = parent.empty() || push.width() <= 0 || push.height() <= 0 || x1 <= x0 || y1 <= y0;
        List<RoundClip> rounds = parent.rounds();
        if (!empty && push.radius() > 0f) {
            rounds = new ArrayList<>(rounds);
            rounds.add(new RoundClip(push.x(), push.y(), push.width(), push.height(), push.radius()));
        }
        return new Clip(x0, y0, x1, y1, true, rounds, empty);
    }

    private void draw(UiDrawCommand command, Clip clip) {
        switch (command) {
            case UiDrawCommand.Rect rect -> solid(rect.x(), rect.y(), rect.width(), rect.height(), rect.radius(),
                    rect.argb(), rect.argb(), rect.argb(), rect.argb(), 0f, clip);
            case UiDrawCommand.GradientRect gr -> {
                if (gr.width() > 0 && gr.height() > 0) solid(gr.x(), gr.y(), gr.width(), gr.height(), gr.radius(),
                        gr.topLeft(), gr.topRight(), gr.bottomRight(), gr.bottomLeft(), 0f, clip);
            }
            case UiDrawCommand.Border border -> {
                if (border.thickness() > 0) solid(border.x(), border.y(), border.width(), border.height(),
                        border.radius(), border.argb(), border.argb(), border.argb(), border.argb(),
                        border.thickness(), clip);
            }
            case UiDrawCommand.Shadow shadow -> shadow(shadow, clip);
            case UiDrawCommand.Text text -> text(text, clip);
            case UiDrawCommand.Image image -> image(image, clip);
            case UiDrawCommand.Effect effect -> effect(effect, clip);
            case UiDrawCommand.Material material -> material(material, clip);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ shapes (gpuui_rect)

    /** Rect, gradient and border: GpuUiRenderer snaps these to whole GUI units before drawing. */
    private void solid(float x, float y, float w, float h, float radius, int tl, int tr, int br, int bl,
                       float borderThickness, Clip clip) {
        if (!(w > 0) || !(h > 0)) return;
        float rx = Math.round(x), ry = Math.round(y);
        float rw = Math.max(1f, Math.round(x + w) - rx), rh = Math.max(1f, Math.round(y + h) - ry);
        float rad = Math.min(Math.max(radius, 0f), Math.min(rw, rh) * 0.5f);
        float border = 0f;
        if (borderThickness > 0f) {
            border = Math.max(1f, Math.round(borderThickness));
            border = Math.max(0f, Math.min(border, Math.min(rw, rh) * 0.5f));
            border = Math.min(32767, Math.round(border * 256f)) / 256f; // UV2 fixed point
        }
        float innerRad = Math.max(rad - border, 0f);
        float innerW = rw - border * 2f, innerH = rh - border * 2f;
        int px0 = Math.max(clip.x0(), (int) Math.ceil(rx * scale - 0.5f));
        int py0 = Math.max(clip.y0(), (int) Math.ceil(ry * scale - 0.5f));
        int px1 = Math.min(clip.x1(), (int) Math.ceil((rx + rw) * scale - 0.5f));
        int py1 = Math.min(clip.y1(), (int) Math.ceil((ry + rh) * scale - 0.5f));
        for (int py = py0; py < py1; py++) {
            float ly = (py + 0.5f) / scale - ry;
            for (int px = px0; px < px1; px++) {
                float lx = (px + 0.5f) / scale - rx;
                float coverage = coverage(lx, ly, rw, rh, rad);
                if (border > 0f && innerW > 0f && innerH > 0f) {
                    coverage *= 1f - coverage(lx - border, ly - border, innerW, innerH, innerRad);
                }
                if (coverage <= 0f) continue;
                coverage *= clipCoverage(px, py, clip);
                if (coverage <= 0f) continue;
                int color = triangleColor(lx / rw, ly / rh, tl, tr, br, bl);
                blend(px, py, color, coverage);
            }
        }
    }

    /** Edge coverage of a rounded rect at a local point, antialiased over one framebuffer pixel. */
    private float coverage(float lx, float ly, float w, float h, float radius) {
        float d = roundedDistance(lx, ly, w, h, radius);
        float step = 1f / scale; // fwidth: finite difference across one pixel, like the GPU's quad derivatives
        float fw = Math.abs(roundedDistance(lx + step, ly, w, h, radius) - d)
                + Math.abs(roundedDistance(lx, ly + step, w, h, radius) - d);
        float edge = Math.max(fw, 1.0e-4f);
        return 1f - smoothstep(-edge, edge, d);
    }

    private static float roundedDistance(float lx, float ly, float w, float h, float radius) {
        float hx = w * 0.5f, hy = h * 0.5f;
        float rad = clamp(radius, 0f, Math.min(hx, hy));
        float qx = Math.abs(lx - hx) - (hx - rad), qy = Math.abs(ly - hy) - (hy - rad);
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - rad;
    }

    /** QUADS are drawn as triangles TL-TR-BR and BR-BL-TL, so colour interpolates per triangle. */
    private static int triangleColor(float u, float v, int tl, int tr, int br, int bl) {
        if (tl == tr && tr == br && br == bl) return tl;
        u = clamp(u, 0f, 1f); v = clamp(v, 0f, 1f);
        float wTl, wTr, wBr, wBl;
        if (u >= v) { wTl = 1f - u; wTr = u - v; wBr = v; wBl = 0f; }
        else { wTl = 1f - v; wBl = v - u; wBr = u; wTr = 0f; }
        float a = 0, rr = 0, gg = 0, bb = 0;
        int[] cs = {tl, tr, br, bl};
        float[] ws = {wTl, wTr, wBr, wBl};
        for (int i = 0; i < 4; i++) {
            a += ((cs[i] >>> 24) & 255) * ws[i];
            rr += ((cs[i] >> 16) & 255) * ws[i];
            gg += ((cs[i] >> 8) & 255) * ws[i];
            bb += (cs[i] & 255) * ws[i];
        }
        return (Math.round(a) << 24) | (Math.round(rr) << 16) | (Math.round(gg) << 8) | Math.round(bb);
    }

    // ------------------------------------------------------------------ gpuui_shadow

    private void shadow(UiDrawCommand.Shadow shadow, Clip clip) {
        float w = shadow.width(), h = shadow.height();
        if (!(w > 0) || !(h > 0)) return;
        float spread = clampFinite(shadow.spread(), 0, 64), softness = clampFinite(shadow.softness(), 0, 64);
        float radius = Math.min(clampFinite(shadow.radius(), 0, Math.min(w, h) * 0.5f), Math.min(w, h) * 0.5f);
        float padding = spread + softness;
        spread = Math.round(spread * 256) / 256f;     // UV2 fixed point, as packed by ShadowState
        softness = Math.round(softness * 256) / 256f;
        float shapeW = Math.max(1, Math.round(w)), shapeH = Math.max(1, Math.round(h));
        float qx0 = shadow.x() - padding, qy0 = shadow.y() - padding;
        float qx1 = qx0 + w + padding * 2, qy1 = qy0 + h + padding * 2;
        int px0 = Math.max(clip.x0(), (int) Math.ceil(qx0 * scale - 0.5f));
        int py0 = Math.max(clip.y0(), (int) Math.ceil(qy0 * scale - 0.5f));
        int px1 = Math.min(clip.x1(), (int) Math.ceil(qx1 * scale - 0.5f));
        int py1 = Math.min(clip.y1(), (int) Math.ceil(qy1 * scale - 0.5f));
        // Local position runs -padding .. shape + padding across the quad (vertex attribute).
        float spanX = (shapeW + padding * 2) / (w + padding * 2), spanY = (shapeH + padding * 2) / (h + padding * 2);
        float step = 1f / scale;
        for (int py = py0; py < py1; py++) {
            float ly = ((py + 0.5f) / scale - qy0) * spanY - padding;
            for (int px = px0; px < px1; px++) {
                float lx = ((px + 0.5f) / scale - qx0) * spanX - padding;
                float d = roundedDistance(lx, ly, shapeW, shapeH, radius) - spread;
                float fw = Math.abs(roundedDistance(lx + step * spanX, ly, shapeW, shapeH, radius) - spread - d)
                        + Math.abs(roundedDistance(lx, ly + step * spanY, shapeW, shapeH, radius) - spread - d);
                float edge = Math.max(fw, 0.35f);
                float falloff = Math.max(softness, edge);
                float coverage = (1f - smoothstep(-edge, falloff, d)) * clipCoverage(px, py, clip);
                if (coverage > 0f) blend(px, py, shadow.argb(), coverage);
            }
        }
    }

    // ------------------------------------------------------------------ gpuui_text (MSDF)

    private void text(UiDrawCommand.Text text, Clip clip) {
        if (text.size() <= 0 || text.text().isEmpty()) return;
        BufferedImage atlas = texture(GpuUiFontMetrics.face(text.weight()).atlasResource());
        if (atlas == null) return;
        GpuUiFontMetrics.layout(text.text(), Math.round(text.x()), Math.round(text.y()), text.size(), text.weight(),
                (left, top, w, h, u0, v0, u1, v1) -> {
            if (!(w > 0) || !(h > 0)) return;
            int px0 = Math.max(clip.x0(), (int) Math.ceil(left * scale - 0.5f));
            int py0 = Math.max(clip.y0(), (int) Math.ceil(top * scale - 0.5f));
            int px1 = Math.min(clip.x1(), (int) Math.ceil((left + w) * scale - 0.5f));
            int py1 = Math.min(clip.y1(), (int) Math.ceil((top + h) * scale - 0.5f));
            float duPx = Math.abs(u1 - u0) / (w * scale), dvPx = Math.abs(v1 - v0) / (h * scale);
            float unit = GpuUiFontMetrics.DISTANCE_RANGE / GpuUiFontMetrics.ATLAS_SIZE;
            float sRange = Math.max(0.5f * (unit / Math.max(duPx, 1e-5f) + unit / Math.max(dvPx, 1e-5f)), 1f);
            for (int py = py0; py < py1; py++) {
                float tv = v0 + (v1 - v0) * (((py + 0.5f) / scale - top) / h);
                for (int px = px0; px < px1; px++) {
                    float tu = u0 + (u1 - u0) * (((px + 0.5f) / scale - left) / w);
                    float[] s = sample(atlas, tu, tv);
                    float median = Math.max(Math.min(s[0], s[1]), Math.min(Math.max(s[0], s[1]), s[2]));
                    float coverage = clamp((median - 0.5f) * sRange + 0.5f, 0f, 1f) * clipCoverage(px, py, clip);
                    if (coverage * ((text.argb() >>> 24) / 255f) > 0.001f) blend(px, py, text.argb(), coverage);
                }
            }
        });
    }

    // ------------------------------------------------------------------ gpuui_image

    private void image(UiDrawCommand.Image image, Clip clip) {
        String path = "assets/" + image.texture().getNamespace() + "/" + image.texture().getPath();
        BufferedImage tex = texture(path);
        if (tex == null) { approximate("missing texture " + path); return; }
        float ix = image.x(), iy = image.y();
        if (!(image.width() > 0) || !(image.height() > 0)) return;
        float x = Math.round(ix), y = Math.round(iy);
        float w = Math.max(1f, Math.round(ix + image.width()) - x), h = Math.max(1f, Math.round(iy + image.height()) - y);
        float rad = Math.min(Math.max(image.radius(), 0), Math.min(w, h) * 0.5f);
        int px0 = Math.max(clip.x0(), (int) Math.ceil(x * scale - 0.5f));
        int py0 = Math.max(clip.y0(), (int) Math.ceil(y * scale - 0.5f));
        int px1 = Math.min(clip.x1(), (int) Math.ceil((x + w) * scale - 0.5f));
        int py1 = Math.min(clip.y1(), (int) Math.ceil((y + h) * scale - 0.5f));
        float ta = (image.argb() >>> 24) / 255f, tr = ((image.argb() >> 16) & 255) / 255f,
                tg = ((image.argb() >> 8) & 255) / 255f, tb = (image.argb() & 255) / 255f;
        for (int py = py0; py < py1; py++) {
            float ly = (py + 0.5f) / scale - y;
            float tv = image.v0() + (image.v1() - image.v0()) * (ly / h);
            for (int px = px0; px < px1; px++) {
                float lx = (px + 0.5f) / scale - x;
                float tu = image.u0() + (image.u1() - image.u0()) * (lx / w);
                float[] s = sample(tex, tu, tv);
                float alpha = s[3] * ta * coverage(lx, ly, w, h, rad) * clipCoverage(px, py, clip);
                if (alpha > 0f) blendRgb(px, py, s[0] * tr, s[1] * tg, s[2] * tb, alpha);
            }
        }
    }

    // ------------------------------------------------------------------ effects and materials

    private void effect(UiDrawCommand.Effect effect, Clip clip) {
        switch (effect.kind()) {
            case GLASS -> glass(effect, clip);
            case BLUR -> blurInPlace(effect, clip);
            default -> approximate("skipped " + effect.kind() + " effect");
        }
    }

    /** gpuui_liquid over a box-blurred world backdrop (the game blurs a world snapshot the same way). */
    private void glass(UiDrawCommand.Effect e, Clip clip) {
        approximate("glass blur approximated (box blur instead of dual kawase)");
        float[][] bg = blurredBackdrop(Math.round(clamp(e.strength(), 0f, 30f) * scale * 0.9f));
        float x = e.x(), y = e.y(), w = e.width(), h = e.height();
        if (!(w > 0) || !(h > 0)) return;
        float hx = w * 0.5f, hy = h * 0.5f;
        float rad = clamp(e.radius(), 0f, Math.min(hx, hy));
        float tintA = (e.argb() >>> 24) / 255f;
        float tr = ((e.argb() >> 16) & 255) / 255f, tg = ((e.argb() >> 8) & 255) / 255f, tb = (e.argb() & 255) / 255f;
        float bevel = Math.max(4f, Math.min(22f, Math.min(hx, hy) * 0.55f));
        int px0 = Math.max(clip.x0(), (int) Math.ceil(x * scale - 0.5f));
        int py0 = Math.max(clip.y0(), (int) Math.ceil(y * scale - 0.5f));
        int px1 = Math.min(clip.x1(), (int) Math.ceil((x + w) * scale - 0.5f));
        int py1 = Math.min(clip.y1(), (int) Math.ceil((y + h) * scale - 0.5f));
        for (int py = py0; py < py1; py++) {
            float ly = (py + 0.5f) / scale - y;
            for (int px = px0; px < px1; px++) {
                float lx = (px + 0.5f) / scale - x;
                float pxl = lx - hx, pyl = ly - hy;
                float qx = Math.abs(pxl) - (hx - rad), qy = Math.abs(pyl) - (hy - rad);
                float dist = roundedDistance(lx, ly, w, h, rad);
                float cov = coverage(lx, ly, w, h, rad) * clipCoverage(px, py, clip);
                if (cov <= 0f) continue;
                float nx, ny;
                if (qx > 0 && qy > 0) { float l = (float) Math.sqrt(qx * qx + qy * qy); nx = qx / l; ny = qy / l; }
                else if (qx > qy) { nx = 1; ny = 0; } else { nx = 0; ny = 1; }
                nx *= pxl < 0 ? -1 : 1; ny *= pyl < 0 ? -1 : 1;
                float edge = 1f - clamp(-dist / bevel, 0f, 1f);
                float lens = edge * edge * (3f - 2f * edge);
                int sx = clampI(Math.round(px - nx * lens * bevel * 0.9f * scale), 0, width - 1);
                int sy = clampI(Math.round(py - ny * lens * bevel * 0.9f * scale), 0, height - 1);
                int si = sy * width + sx;
                float br = bg[0][si], bgc = bg[1][si], bb = bg[2][si];
                float luma = 0.299f * br + 0.587f * bgc + 0.114f * bb;
                float or = (luma + (br - luma) * 0.85f) * 0.92f + 0.035f;
                float og = (luma + (bgc - luma) * 0.85f) * 0.92f + 0.035f;
                float ob = (luma + (bb - luma) * 0.85f) * 0.92f + 0.035f;
                or += (tr - or) * tintA; og += (tg - og) * tintA; ob += (tb - ob) * tintA;
                float facing = Math.max(nx * -0.552f + ny * -0.834f, 0f);
                float rim = (1f - smoothstep(0f, 1.6f / scale + 1.4f / scale, -dist)) * (0.28f + 0.72f * facing);
                float glow = edge * edge * edge * (0.10f + 0.22f * facing);
                float sheen = (1f - smoothstep(0f, 1f, ly / Math.max(h, 1f))) * 0.045f;
                float add = rim * 0.55f + glow + sheen;
                blendRgb(px, py, or + add, og + add, ob + add, cov);
            }
        }
    }

    private void blurInPlace(UiDrawCommand.Effect e, Clip clip) {
        approximate("ambient scene blur approximated");
        float[][] bg = blurredBackdrop(Math.round(clamp(e.strength(), 0f, 30f) * scale * 0.9f));
        int px0 = Math.max(clip.x0(), (int) Math.floor(e.x() * scale)), py0 = Math.max(clip.y0(), (int) Math.floor(e.y() * scale));
        int px1 = Math.min(clip.x1(), (int) Math.ceil((e.x() + e.width()) * scale));
        int py1 = Math.min(clip.y1(), (int) Math.ceil((e.y() + e.height()) * scale));
        for (int py = py0; py < py1; py++) for (int px = px0; px < px1; px++) {
            int i = py * width + px;
            r[i] = bg[0][i]; g[i] = bg[1][i]; b[i] = bg[2][i];
        }
    }

    /** Shader materials (marble, mist, granite...) render on the GPU; stand in with soft noise. */
    private void material(UiDrawCommand.Material m, Clip clip) {
        String shader = m.material().shader().getPath();
        if (shader.contains("fog")) { approximate("fog material skipped"); return; }
        approximate("material " + shader + " approximated with noise");
        float x = m.x(), y = m.y(), w = m.width(), h = m.height();
        if (!(w > 0) || !(h > 0)) return;
        int tint = m.material().tint();
        float ta = (tint >>> 24) / 255f, tr = ((tint >> 16) & 255) / 255f, tg = ((tint >> 8) & 255) / 255f, tb = (tint & 255) / 255f;
        float rad = Math.min(Math.max(m.radius(), 0), Math.min(w, h) * 0.5f);
        int px0 = Math.max(clip.x0(), (int) Math.ceil(x * scale - 0.5f)), py0 = Math.max(clip.y0(), (int) Math.ceil(y * scale - 0.5f));
        int px1 = Math.min(clip.x1(), (int) Math.ceil((x + w) * scale - 0.5f)), py1 = Math.min(clip.y1(), (int) Math.ceil((y + h) * scale - 0.5f));
        for (int py = py0; py < py1; py++) for (int px = px0; px < px1; px++) {
            float lx = (px + 0.5f) / scale - x, ly = (py + 0.5f) / scale - y;
            float n = fbm(lx * 0.012f, ly * 0.012f);
            float v = 0.05f + 0.22f * n;
            float cov = coverage(lx, ly, w, h, rad) * clipCoverage(px, py, clip) * ta;
            if (cov > 0f) blendRgb(px, py, v * tr, v * tg, v * tb, cov);
        }
    }

    // ------------------------------------------------------------------ clip masks and blending

    private float clipCoverage(int px, int py, Clip clip) {
        if (clip.rounds().isEmpty()) return 1f;
        float gx = (px + 0.5f) / scale, gy = (py + 0.5f) / scale;
        float cov = 1f;
        for (RoundClip rc : clip.rounds()) {
            float rad = clamp(rc.radius(), 0f, Math.min(rc.w(), rc.h()) * 0.5f);
            cov *= coverage(gx - rc.x(), gy - rc.y(), rc.w(), rc.h(), rad);
            if (cov <= 0f) return 0f;
        }
        return cov;
    }

    private void blend(int px, int py, int argb, float coverage) {
        float a = ((argb >>> 24) / 255f) * coverage;
        blendRgb(px, py, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, a);
    }

    private void blendRgb(int px, int py, float sr, float sg, float sb, float a) {
        if (px < 0 || py < 0 || px >= width || py >= height || a <= 0f) return;
        a = Math.min(1f, a);
        int i = py * width + px;
        r[i] = sr * a + r[i] * (1f - a);
        g[i] = sg * a + g[i] * (1f - a);
        b[i] = sb * a + b[i] * (1f - a);
    }

    // ------------------------------------------------------------------ resources and helpers

    private BufferedImage texture(String path) {
        return textures.computeIfAbsent(path, p -> {
            try (InputStream in = SoftwareUiRasterizer.class.getClassLoader().getResourceAsStream(p)) {
                return in == null ? null : ImageIO.read(in);
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Bilinear sample, normalised UV, clamp-to-edge. Returns r, g, b, a in 0..1. */
    private static float[] sample(BufferedImage tex, float u, float v) {
        float fx = u * tex.getWidth() - 0.5f, fy = v * tex.getHeight() - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        float[] out = new float[4];
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            int c = tex.getRGB(clampI(x0 + i, 0, tex.getWidth() - 1), clampI(y0 + j, 0, tex.getHeight() - 1));
            float wgt = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
            out[0] += ((c >> 16) & 255) / 255f * wgt;
            out[1] += ((c >> 8) & 255) / 255f * wgt;
            out[2] += (c & 255) / 255f * wgt;
            out[3] += ((c >>> 24) & 255) / 255f * wgt;
        }
        return out;
    }

    private float[][] blurredBackdrop(int radius) {
        return blurredBackdrops.computeIfAbsent(Math.max(1, radius), rad -> {
            float[][] ch = new float[3][width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int c = backdrop.getRGB(x, y), i = y * width + x;
                ch[0][i] = ((c >> 16) & 255) / 255f; ch[1][i] = ((c >> 8) & 255) / 255f; ch[2][i] = (c & 255) / 255f;
            }
            int boxR = Math.max(1, rad / 2);
            for (float[] channel : ch) for (int pass = 0; pass < 3; pass++) { boxH(channel, boxR); boxV(channel, boxR); }
            return ch;
        });
    }

    private void boxH(float[] c, int rad) {
        float[] row = new float[width];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            float sum = 0; int n = 0;
            for (int x = -rad; x <= rad; x++) { sum += c[base + clampI(x, 0, width - 1)]; n++; }
            for (int x = 0; x < width; x++) {
                row[x] = sum / n;
                sum += c[base + clampI(x + rad + 1, 0, width - 1)] - c[base + clampI(x - rad, 0, width - 1)];
            }
            System.arraycopy(row, 0, c, base, width);
        }
    }

    private void boxV(float[] c, int rad) {
        float[] col = new float[height];
        for (int x = 0; x < width; x++) {
            float sum = 0; int n = 0;
            for (int y = -rad; y <= rad; y++) { sum += c[clampI(y, 0, height - 1) * width + x]; n++; }
            for (int y = 0; y < height; y++) {
                col[y] = sum / n;
                sum += c[clampI(y + rad + 1, 0, height - 1) * width + x] - c[clampI(y - rad, 0, height - 1) * width + x];
            }
            for (int y = 0; y < height; y++) c[y * width + x] = col[y];
        }
    }

    private void approximate(String note) {
        if (!approximations.contains(note)) approximations.add(note);
    }

    private static float fbm(float x, float y) {
        float sum = 0, amp = 0.5f;
        for (int o = 0; o < 4; o++) { sum += amp * valueNoise(x, y); x *= 2.03f; y *= 2.03f; amp *= 0.5f; }
        return sum;
    }

    private static float valueNoise(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float tx = x - xi, ty = y - yi;
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
        float a = hash(xi, yi), bb = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return (a + (bb - a) * tx) + ((c + (d - c) * tx) - (a + (bb - a) * tx)) * ty;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xffff) / 65535f;
    }

    static float smoothstep(float e0, float e1, float x) {
        if (e0 == e1) return x < e0 ? 0f : 1f;
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampFinite(float v, float lo, float hi) { return Float.isFinite(v) ? clamp(v, lo, hi) : lo; }
    private static int to8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }
}
