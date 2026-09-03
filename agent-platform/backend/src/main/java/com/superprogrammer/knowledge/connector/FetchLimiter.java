package com.superprogrammer.knowledge.connector;

/**
 * C6 抓取节流（WP6 Step2）：限速（默认 1 req/s，同步不砸源站）+ 总字节闸（默认 200MB，
 * 单轮同步黑洞兜底）。每轮同步一个实例，连接器实现每次出站请求前 {@link #acquire()}、
 * 拿到响应体后 {@link #chargeBytes(long)}。
 */
public class FetchLimiter {

    private final long minIntervalMs;
    private final long maxTotalBytes;
    private long lastCallAt;
    private long totalBytes;

    public FetchLimiter() {
        this(1000L, 200L * 1024 * 1024);
    }

    public FetchLimiter(long minIntervalMs, long maxTotalBytes) {
        this.minIntervalMs = minIntervalMs;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** 出站前调用：距上次出站不足间隔则补睡（同步阻塞——worker 单线程顺序拉取，简单即正确）。 */
    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        long wait = lastCallAt + minIntervalMs - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("抓取被中断", e);
            }
        }
        lastCallAt = System.currentTimeMillis();
    }

    /** 响应体计量：累计超总闸抛异常终止本轮（worker 捕获记 last_sync_summary）。 */
    public synchronized void chargeBytes(long bytes) {
        if (bytes < 0) {
            return;
        }
        totalBytes += bytes;
        if (totalBytes > maxTotalBytes) {
            throw new IllegalStateException("单轮同步总字节超上限 " + maxTotalBytes + "（已拉 " + totalBytes + "）");
        }
    }

    public synchronized long totalBytes() {
        return totalBytes;
    }
}
