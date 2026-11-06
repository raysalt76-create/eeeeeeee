package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.BufferAllocator;

import java.util.ArrayDeque;

/**
 * A {@link BufferAllocator} that goes back to a small render-thread pool when closed instead of freeing its
 * native memory.
 *
 * <p>World-render code used to build {@code new BufferAllocator(n)} every frame (up to several MB for some
 * effects), i.e. a native malloc + free per module per frame. Large native allocations are served by the OS
 * page allocator, so that churn shows up as frame-time spikes. {@link #acquire(int)} hands out a recycled
 * allocator on the client (render) thread; {@code close()} clears it and returns it to the pool, so existing
 * {@code try (BufferAllocator a = PooledBufferAllocator.acquire(n))} and explicit {@code close()} call sites keep
 * their shape. A pool slot is used by one caller at a time, so nested acquisitions get separate allocators.
 * Off the client thread it falls back to an ordinary, unpooled allocator.</p>
 */
public final class PooledBufferAllocator extends BufferAllocator {
    private static final int MAX_POOLED = 4;
    /** Allocators that grew past this go back to native free on close instead of being retained in the pool. */
    private static final long MAX_RETAINED_BYTES = 512L * 1024;
    // Client/render thread only.
    private static final ArrayDeque<PooledBufferAllocator> POOL = new ArrayDeque<>(MAX_POOLED);

    private boolean leased;
    /** Bytes handed out since the last clear, and the most ever handed out (approximates the native size). */
    private long usedSinceClear;
    private long peakBytes;

    private PooledBufferAllocator(int size) {
        super(size);
        peakBytes = size;
    }

    /** An allocator for one render pass. Close it (try-with-resources) when the pass has drawn. */
    public static BufferAllocator acquire(int initialSize) {
        if (!onRenderThread()) return new BufferAllocator(initialSize);
        PooledBufferAllocator allocator = POOL.pollLast();
        if (allocator == null) allocator = new PooledBufferAllocator(Math.max(256, initialSize));
        allocator.leased = true;
        return allocator;
    }

    @Override
    public void close() {
        if (!leased) return; // double close: already back in the pool (or freed)
        leased = false;
        if (onRenderThread() && POOL.size() < MAX_POOLED && peakBytes <= MAX_RETAINED_BYTES) {
            // Drops anything a caller built but never drew; stale CloseableBuffers become no-ops.
            clear();
            POOL.addLast(this);
        } else {
            super.close();
        }
    }

    @Override
    public long allocate(int bytes) {
        long pointer = super.allocate(bytes);
        usedSinceClear += bytes;
        if (usedSinceClear > peakBytes) peakBytes = usedSinceClear;
        return pointer;
    }

    @Override
    public void clear() {
        super.clear();
        usedSinceClear = 0;
    }

    @Override
    public void reset() {
        super.reset();
        usedSinceClear = 0;
    }

    private static boolean onRenderThread() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            return client != null && client.isOnThread();
        } catch (Throwable unavailable) {
            return false;
        }
    }
}
