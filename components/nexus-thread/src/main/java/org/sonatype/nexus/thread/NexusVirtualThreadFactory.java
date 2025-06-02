package org.sonatype.nexus.thread;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class NexusVirtualThreadFactory implements ThreadFactory {
    private static final String DEFAULT_PREFIX = "nexus-virtual";
    private final String prefix;
    private final ThreadGroup group;
    private final boolean daemon;
    private final int priority;
    private final AtomicLong count = new AtomicLong();

    public NexusVirtualThreadFactory(String prefix) {
        this(prefix, false);
    }

    public NexusVirtualThreadFactory(String prefix, boolean daemon) {
        this(prefix, daemon, Thread.NORM_PRIORITY, null);
    }

    public NexusVirtualThreadFactory(String prefix, int priority) {
        this(prefix, false, priority, null);
    }

    public NexusVirtualThreadFactory(String prefix, ThreadGroup group) {
        this(prefix, false, Thread.NORM_PRIORITY, group);
    }

    public NexusVirtualThreadFactory(String prefix, boolean daemon, int priority, ThreadGroup group) {
        this.prefix = (prefix == null || prefix.isEmpty()) ? DEFAULT_PREFIX : prefix;
        this.daemon = daemon;
        this.priority = priority;
        this.group = group;
    }

    @Override
    public Thread newThread(Runnable r) {
        String name = prefix + "-" + count.incrementAndGet();
        Thread thread = Thread.ofVirtual().name(name).unstarted(r);
        // Virtual threads are always daemon and ignore priority, but set for API compatibility
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }
}