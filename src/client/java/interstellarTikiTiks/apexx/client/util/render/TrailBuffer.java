package interstellarTikiTiks.apexx.client.util.render;

/**
 * Fixed-capacity ring buffer of timestamped 3D points for trails. Index 0 is the oldest point and
 * {@code size() - 1} the newest. When full, adding a point drops the oldest one. Nothing is allocated
 * after construction unless the capacity changes, so a trail can be sampled every frame.
 *
 * <p>Pure Java, not thread-safe: use it from one thread (the client/render thread).</p>
 */
public final class TrailBuffer {
    private double[] xs;
    private double[] ys;
    private double[] zs;
    private long[] ts;
    private int head; // physical index of the oldest point
    private int count;

    public TrailBuffer(int capacity) {
        allocate(Math.max(2, capacity));
    }

    private void allocate(int capacity) {
        xs = new double[capacity];
        ys = new double[capacity];
        zs = new double[capacity];
        ts = new long[capacity];
        head = 0;
        count = 0;
    }

    public int capacity() {
        return xs.length;
    }

    /** Changes the capacity, keeping the newest points that still fit. No-op when unchanged. */
    public void setCapacity(int capacity) {
        int cap = Math.max(2, capacity);
        if (cap == xs.length) return;
        int keep = Math.min(count, cap);
        double[] nx = new double[cap];
        double[] ny = new double[cap];
        double[] nz = new double[cap];
        long[] nt = new long[cap];
        int skip = count - keep;
        for (int i = 0; i < keep; i++) {
            int src = physical(skip + i);
            nx[i] = xs[src];
            ny[i] = ys[src];
            nz[i] = zs[src];
            nt[i] = ts[src];
        }
        xs = nx;
        ys = ny;
        zs = nz;
        ts = nt;
        head = 0;
        count = keep;
    }

    private int physical(int index) {
        int p = head + index;
        return p >= xs.length ? p - xs.length : p;
    }

    public void add(double x, double y, double z, long time) {
        int slot;
        if (count < xs.length) {
            slot = physical(count);
            count++;
        } else {
            slot = head;
            head = head + 1 == xs.length ? 0 : head + 1;
        }
        xs[slot] = x;
        ys[slot] = y;
        zs[slot] = z;
        ts[slot] = time;
    }

    /** Removes points whose time is strictly before {@code cutoff}. Points are assumed time-ordered. */
    public void pruneOlderThan(long cutoff) {
        while (count > 0 && ts[head] < cutoff) {
            head = head + 1 == xs.length ? 0 : head + 1;
            count--;
        }
        if (count == 0) head = 0;
    }

    public void clear() {
        head = 0;
        count = 0;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public double x(int i) {
        return xs[physical(i)];
    }

    public double y(int i) {
        return ys[physical(i)];
    }

    public double z(int i) {
        return zs[physical(i)];
    }

    public long time(int i) {
        return ts[physical(i)];
    }

    /** Squared distance from the newest point to (x, y, z); +infinity when empty. */
    public double distanceSqToNewest(double x, double y, double z) {
        if (count == 0) return Double.POSITIVE_INFINITY;
        int p = physical(count - 1);
        double dx = xs[p] - x;
        double dy = ys[p] - y;
        double dz = zs[p] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public long newestTime() {
        return count == 0 ? Long.MIN_VALUE : ts[physical(count - 1)];
    }
}
