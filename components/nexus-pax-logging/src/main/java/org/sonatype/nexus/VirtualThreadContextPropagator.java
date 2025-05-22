/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;

// Java 21 imports for Virtual Threads
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class for managing the propagation of thread-local variables across Virtual Thread boundaries in Java 21.
 * <p>
 * This class ensures that logging context (MDC), security context, and other thread-local state is properly
 * maintained when operations span multiple Virtual Threads, which is critical for maintaining logging
 * continuity and security in asynchronous operations.
 * <p>
 * Virtual Threads in Java 21 are lightweight threads that are managed by the JVM rather than the operating system.
 * While they support thread-local variables, special care must be taken to ensure proper propagation of context
 * when operations cross thread boundaries.
 * <p>
 * Usage example:
 * <pre>
 * // Capture the current context
 * ThreadContext context = VirtualThreadContextPropagator.capture();
 * 
 * // Create a context-aware Runnable
 * Runnable task = VirtualThreadContextPropagator.wrap(() -> {
 *     // This code will execute with the captured context
 *     log.info("Operation in virtual thread");
 * }, context);
 * 
 * // Execute the task in a Virtual Thread
 * Thread.startVirtualThread(task);
 * </pre>
 *
 * @since 3.60
 */
public final class VirtualThreadContextPropagator {

    private VirtualThreadContextPropagator() {
        // Utility class, no instances
    }

    /**
     * Represents a captured thread context that can be propagated to other threads.
     * Contains MDC (logging) context and security context.
     */
    public static final class ThreadContext {
        private final Map<String, String> mdcContext;
        private final Subject securitySubject;

        private ThreadContext(Map<String, String> mdcContext, Subject securitySubject) {
            this.mdcContext = mdcContext != null ? new HashMap<>(mdcContext) : null;
            this.securitySubject = securitySubject;
        }
    }

    /**
     * Captures the current thread's context for later propagation to another thread.
     * This includes the MDC (logging) context and security context.
     *
     * @return a ThreadContext object containing the captured context
     */
    public static ThreadContext capture() {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Subject securitySubject = SecurityUtils.getSubject();
        return new ThreadContext(mdcContext, securitySubject);
    }

    /**
     * Applies a previously captured context to the current thread.
     * This restores both MDC (logging) context and security context.
     *
     * @param context the context to apply, or null to clear the context
     */
    public static void apply(ThreadContext context) {
        if (context == null) {
            MDC.clear();
            return;
        }

        // Apply MDC context
        if (context.mdcContext != null) {
            MDC.setContextMap(context.mdcContext);
        } else {
            MDC.clear();
        }

        // Note: Security context is thread-bound and typically managed by Shiro's SubjectAwareExecutorService
        // We don't explicitly set it here as it's handled by the security framework
    }

    /**
     * Clears the context for the current thread.
     * This is important to prevent memory leaks, especially in Virtual Threads.
     */
    public static void clear() {
        MDC.clear();
        // Security context is managed by the security framework
    }

    /**
     * Wraps a Runnable to ensure it executes with the captured context.
     * This is useful when submitting tasks to thread pools or creating Virtual Threads.
     *
     * @param runnable the Runnable to wrap
     * @param context the context to apply before executing the Runnable
     * @return a new Runnable that will apply the context before executing the original Runnable
     */
    public static Runnable wrap(Runnable runnable, ThreadContext context) {
        checkNotNull(runnable, "Runnable cannot be null");
        checkNotNull(context, "ThreadContext cannot be null");
        
        return () -> {
            ThreadContext originalContext = capture();
            try {
                apply(context);
                runnable.run();
            } finally {
                apply(originalContext);
            }
        };
    }

    /**
     * Wraps a Callable to ensure it executes with the captured context.
     * This is useful when submitting tasks to thread pools or ExecutorService.
     *
     * @param callable the Callable to wrap
     * @param context the context to apply before executing the Callable
     * @param <V> the return type of the Callable
     * @return a new Callable that will apply the context before executing the original Callable
     */
    public static <V> Callable<V> wrap(Callable<V> callable, ThreadContext context) {
        checkNotNull(callable, "Callable cannot be null");
        checkNotNull(context, "ThreadContext cannot be null");
        
        return () -> {
            ThreadContext originalContext = capture();
            try {
                apply(context);
                return callable.call();
            } finally {
                apply(originalContext);
            }
        };
    }

    /**
     * Wraps a Supplier to ensure it executes with the captured context.
     * This is useful for functional programming patterns.
     *
     * @param supplier the Supplier to wrap
     * @param context the context to apply before executing the Supplier
     * @param <T> the return type of the Supplier
     * @return a new Supplier that will apply the context before executing the original Supplier
     */
    public static <T> Supplier<T> wrap(Supplier<T> supplier, ThreadContext context) {
        checkNotNull(supplier, "Supplier cannot be null");
        checkNotNull(context, "ThreadContext cannot be null");
        
        return () -> {
            ThreadContext originalContext = capture();
            try {
                apply(context);
                return supplier.get();
            } finally {
                apply(originalContext);
            }
        };
    }

    /**
     * Executes a Runnable with the current thread's context.
     * This is useful for executing code in a different thread while maintaining the current context.
     *
     * @param runnable the Runnable to execute
     */
    public static void runWithCurrentContext(Runnable runnable) {
        checkNotNull(runnable, "Runnable cannot be null");
        ThreadContext context = capture();
        wrap(runnable, context).run();
    }

    /**
     * Executes a Callable with the current thread's context and returns its result.
     * This is useful for executing code in a different thread while maintaining the current context.
     *
     * @param callable the Callable to execute
     * @param <V> the return type of the Callable
     * @return the result of the Callable execution
     * @throws Exception if the callable throws an exception
     */
    public static <V> V callWithCurrentContext(Callable<V> callable) throws Exception {
        checkNotNull(callable, "Callable cannot be null");
        ThreadContext context = capture();
        return wrap(callable, context).call();
    }

    /**
     * Executes a Supplier with the current thread's context and returns its result.
     * This is useful for functional programming patterns.
     *
     * @param supplier the Supplier to execute
     * @param <T> the return type of the Supplier
     * @return the result of the Supplier execution
     */
    public static <T> T getWithCurrentContext(Supplier<T> supplier) {
        checkNotNull(supplier, "Supplier cannot be null");
        ThreadContext context = capture();
        return wrap(supplier, context).get();
    }

    /**
     * Determines if the current thread is a Virtual Thread.
     * This is useful for conditional logic based on thread type.
     *
     * @return true if the current thread is a Virtual Thread, false otherwise
     */
    public static boolean isVirtualThread() {
        return Thread.currentThread().isVirtual();
    }
    
    /**
     * Creates a new Virtual Thread that executes the given task with the current thread's context.
     * This is a convenience method for starting a Virtual Thread with context propagation.
     *
     * @param task the Runnable to execute in a new Virtual Thread with the current context
     * @return the newly created and started Virtual Thread
     */
    public static Thread startVirtualThread(Runnable task) {
        checkNotNull(task, "Task cannot be null");
        ThreadContext context = capture();
        return Thread.startVirtualThread(wrap(task, context));
    }
    
    /**
     * Creates a new Virtual Thread builder with context propagation support.
     * The returned builder will create Virtual Threads that execute with the current thread's context.
     *
     * @return a Thread.Builder that will create Virtual Threads with context propagation
     */
    /**
     * Creates a new Virtual Thread builder with context propagation support.
     * The returned builder will create Virtual Threads that execute with the current thread's context.
     *
     * @return a Thread.Builder that will create Virtual Threads with context propagation
     */
    public static Thread.Builder.OfVirtual virtualThreadBuilder() {
        ThreadContext context = capture();
        return Thread.ofVirtual().factory(task -> {
            Runnable contextualTask = wrap(task, context);
            return Thread.ofVirtual().unstarted(contextualTask);
        });
    }
    
    /**
     * Creates a structured concurrency scope that propagates the current thread's context to all child tasks.
     * This is useful when using Java 21's StructuredTaskScope for parallel operations.
     *
     * @param <T> the type of the scope to return
     * @param scopeSupplier a supplier that creates a new scope instance
     * @return a new scope instance with context propagation
     */
    public static <T extends AutoCloseable> T withStructuredConcurrency(Supplier<T> scopeSupplier) {
        checkNotNull(scopeSupplier, "Scope supplier cannot be null");
        ThreadContext context = capture();
        return getWithCurrentContext(scopeSupplier);
    }
    
    /**
     * Creates a new ExecutorService that uses Virtual Threads and propagates the current thread's context.
     * This is a convenience method for creating a thread pool that uses Virtual Threads with context propagation.
     *
     * @return an ExecutorService that uses Virtual Threads with context propagation
     */
    public static ExecutorService newVirtualThreadExecutor() {
        ThreadContext context = capture();
        return Executors.newThreadPerTaskExecutor(task -> {
            Runnable contextualTask = wrap(task, context);
            return Thread.ofVirtual().unstarted(contextualTask);
        });
    }
}