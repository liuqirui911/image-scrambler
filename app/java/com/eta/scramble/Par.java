package com.eta.scramble;

/**
 * 极轻量的并行区间工具。
 *
 * 本应用里的所有变换都是"每个输出像素各自算一个结果、彼此不重叠"的置换或异或，
 * 因此把输出区间纵向切开交给多个线程，结果与串行版本逐位一致
 * （用 dev/ParityTest 与 v1.6 旧实现做全格式对拍校验）。
 *
 * 为什么不用 GPU：这些算法的核心代价是离散访存（逐像素置换、散列写回），
 * 而不是算术吞吐；GPU 在这种随机 gather/scatter 上并不比多核 CPU 有优势，
 * 还要付出纹理上传/回读、上下文创建和（Vulkan/NDK 方案）成百上千 KB 体积的代价。
 * 多核调度则是零体积成本，实测 8 核可把还原耗时压到 1/5 左右。
 *
 * 任务量低于阈值时直接在当前线程跑，避免线程创建开销超过计算本身。
 */
final class Par {

    /** 少于这个像素量就直接串行：线程创建+唤醒约几十微秒，图片太小时不划算。 */
    private static final int MIN_PARALLEL_WORK = 1 << 18; // 262144 像素

    /** 最多用这么多线程：手机 SoC 通常是 4-8 核，再多了只会互相抢内存带宽。 */
    private static final int MAX_THREADS = 8;

    interface Range {
        /** 处理 [from, to) 区间。同一个任务的不同区间之间不得有写冲突。 */
        void run(int from, int to);
    }

    interface Indexed {
        /** 处理第 index 个任务。不同任务之间不得有写冲突。 */
        void run(int index);
    }

    private Par() {}

    static int threads() {
        int n = Runtime.getRuntime().availableProcessors();
        if (n < 1) return 1;
        return n > MAX_THREADS ? MAX_THREADS : n;
    }

    /**
     * 把 [0, total) 切开并行执行。
     *
     * @param total 区间总数（例如行数、像素数）
     * @param work  预计处理的像素量，用来判断值不值得开线程
     */
    static void each(int total, long work, final Range task) {
        if (total <= 0) return;
        int t = threads();
        if (t <= 1 || total == 1 || work < MIN_PARALLEL_WORK) {
            task.run(0, total);
            return;
        }
        if (total < t) t = total;
        final int chunk = (total + t - 1) / t;
        Thread[] workers = new Thread[t];
        final Throwable[] failure = new Throwable[1];
        int started = 0;
        for (int i = 0; i < t; i++) {
            final int from = i * chunk;
            if (from >= total) break;
            final int to = Math.min(total, from + chunk);
            Thread th = new Thread("scramble-" + i) {
                @Override public void run() {
                    try {
                        task.run(from, to);
                    } catch (Throwable e) {
                        synchronized (failure) {
                            if (failure[0] == null) failure[0] = e;
                        }
                    }
                }
            };
            try {
                th.start();
            } catch (Throwable e) {
                // 线程创建失败（内存紧张等）：余下区间在当前线程串行做完，结果与并行完全相同
                task.run(from, total);
                break;
            }
            workers[started++] = th;
        }
        for (int i = 0; i < started; i++) {
            try {
                workers[i].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        rethrow(failure[0]);
    }

    /**
     * 并行跑 count 个互不干扰的小任务：前 count-1 个各起一个线程，最后一个留在当前线程做。
     * 线程创建失败就在当前线程补做，行为与全部起线程时完全相同（不会因为并行而失败）。
     */
    static void forEachIndex(final int count, final Indexed task) {
        if (count <= 0) return;
        int started = 0;
        Thread[] workers = new Thread[count];
        final Throwable[] failure = new Throwable[1];
        for (int i = 0; i < count - 1; i++) {
            final int index = i;
            Thread th = new Thread("scramble-part-" + i) {
                @Override public void run() {
                    try {
                        task.run(index);
                    } catch (Throwable e) {
                        synchronized (failure) {
                            if (failure[0] == null) failure[0] = e;
                        }
                    }
                }
            };
            try {
                th.start();
            } catch (Throwable e) {
                break; // 起不了线程：剩下的（含本次）都在当前线程补做
            }
            workers[started++] = th;
        }
        for (int i = started; i < count; i++) {
            task.run(i);
        }
        for (int i = 0; i < started; i++) {
            try {
                workers[i].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        rethrow(failure[0]);
    }

    private static void rethrow(Throwable f) {
        if (f instanceof RuntimeException) throw (RuntimeException) f;
        if (f instanceof Error) throw (Error) f; // 包括 OutOfMemoryError，交给上层统一错误处理
        if (f != null) throw new IllegalStateException(f);
    }
}
