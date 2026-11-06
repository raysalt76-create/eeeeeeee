package interstellarTikiTiks.apexx.client.rendering;

import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ParticleBackground {

    private static final Random RANDOM = new Random();

    private int screenWidth;
    private int screenHeight;

    private final List<Particle> particles = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();

    private static final int PARTICLE_COUNT = 60;
    private static final int SPARK_COUNT = 25;

    private static final int[] COLORS = {
            0xFF3311BB, 0xFF5522DD, 0xFF7744FF,
            0xFF4422CC, 0xFF9955FF, 0xFF2200AA,
            0xFF6633EE, 0xFF8822DD
    };

    public void init(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        particles.clear();
        sparks.clear();

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles.add(spawnParticle(true));
        }
        for (int i = 0; i < SPARK_COUNT; i++) {
            sparks.add(spawnSpark(true));
        }
    }

    private Particle spawnParticle(boolean randomY) {
        Particle p = new Particle();
        p.x = RANDOM.nextFloat() * screenWidth;
        p.y = randomY ? RANDOM.nextFloat() * screenHeight : screenHeight + 4;
        p.vx = (RANDOM.nextFloat() - 0.5f) * 0.3f;
        p.vy = -(0.15f + RANDOM.nextFloat() * 0.4f);
        p.size = 1 + RANDOM.nextInt(3);
        p.alpha = 0.3f + RANDOM.nextFloat() * 0.7f;
        p.fadeSpeed = 0.001f + RANDOM.nextFloat() * 0.003f;
        p.color = COLORS[RANDOM.nextInt(COLORS.length)];
        p.life = 1.0f;
        return p;
    }

    private Spark spawnSpark(boolean randomPos) {
        Spark s = new Spark();
        s.x = RANDOM.nextFloat() * screenWidth;
        s.y = randomPos ? RANDOM.nextFloat() * screenHeight : screenHeight + 2;
        float angle = (float) (Math.PI * 1.5 + (RANDOM.nextFloat() - 0.5f) * 1.2f);
        float speed = 0.8f + RANDOM.nextFloat() * 1.8f;
        s.vx = (float) Math.cos(angle) * speed;
        s.vy = (float) Math.sin(angle) * speed;
        s.life = 1.0f;
        s.decay = 0.008f + RANDOM.nextFloat() * 0.015f;
        s.length = 3 + RANDOM.nextInt(5);
        s.color = RANDOM.nextBoolean() ? 0xFFAA66FF : 0xFF6644FF;
        return s;
    }

    public void render(DrawContext context, float delta) {
        float dt = Math.min(delta, 3.0f);

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.life -= p.fadeSpeed * dt;

            if (p.life <= 0 || p.y < -10 || p.x < -10 || p.x > screenWidth + 10) {
                particles.set(i, spawnParticle(false));
                continue;
            }

            int alpha = (int) (p.alpha * p.life * 255);
            alpha = Math.max(0, Math.min(255, alpha));
            int color = (p.color & 0x00FFFFFF) | (alpha << 24);

            int px = (int) p.x;
            int py = (int) p.y;
            int sz = p.size;
            context.fill(px, py, px + sz, py + sz, color);
        }

        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.vy += 0.02f * dt;
            s.life -= s.decay * dt;

            if (s.life <= 0 || s.y < -20 || s.y > screenHeight + 20) {
                sparks.set(i, spawnSpark(false));
                continue;
            }

            int alpha = (int) (s.life * 220);
            alpha = Math.max(0, Math.min(255, alpha));
            int color = (s.color & 0x00FFFFFF) | (alpha << 24);

            float tailX = s.x - s.vx * s.length;
            float tailY = s.y - s.vy * s.length;

            drawLine(context, s.x, s.y, tailX, tailY, color);
        }
    }

    private void drawLine(DrawContext context, float x1, float y1, float x2, float y2, int color) {
        int steps = Math.max(1, (int) Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int px = (int) (x1 + (x2 - x1) * t);
            int py = (int) (y1 + (y2 - y1) * t);
            context.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static class Particle {
        float x, y, vx, vy;
        float alpha, fadeSpeed, life;
        int size, color;
    }

    private static class Spark {
        float x, y, vx, vy;
        float life, decay, length;
        int color;
    }
}
