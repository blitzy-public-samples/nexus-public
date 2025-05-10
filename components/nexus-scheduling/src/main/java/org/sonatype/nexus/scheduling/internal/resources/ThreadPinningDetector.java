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
package org.sonatype.nexus.scheduling.internal.resources;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import com.google.common.annotations.VisibleForTesting;

/**
 * Utility class that detects thread-pinning scenarios in JAX-RS resource methods that could prevent
 * carrier thread release when using Virtual Threads.
 * <p>
 * In Java 21, Virtual Threads are designed to be unmounted from their carrier threads when they block
 * on I/O operations. However, certain code patterns can prevent this unmounting, causing the carrier
 * thread to remain blocked. This is known as "thread pinning" and can severely impact performance
 * and scalability in high-concurrency environments.
 * <p>
 * This detector provides runtime checks to identify operations that could block the carrier thread:
 * <ul>
 *   <li>Synchronization on non-Thread objects (synchronized blocks/methods)</li>
 *   <li>Blocking I/O without proper virtual thread dispatch</li>
 *   <li>Direct native method calls</li>
 * </ul>
 * <p>
 * When these patterns are detected, warnings are logged with suggested remediation.
 * <p>
 * Example usage in a JAX-RS resource:
 * <pre>
 * {@code
 * @Inject
 * private ThreadPinningDetector threadPinningDetector;
 * 
 * @GET
 * @Path("/example")
 * public Response getExample() {
 *   // Check for potential pinning issues
 *   threadPinningDetector.detectPinningIssues(getClass(), "getExample");
 *   
 *   // Use ReentrantLock instead of synchronized
 *   ReentrantLock lock = new ReentrantLock();
 *   return threadPinningDetector.supplyWithLock(() -> {
 *     // Critical section code here
 *     return Response.ok().build();
 *   }, lock);
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
@Named
@Singleton
public class ThreadPinningDetector extends ComponentSupport
{
  private static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "jdk.virtualThreadScheduler.parallelism";
  private static final String JAVA_VERSION_PROPERTY = "java.version";
  private static final String JAVA_21_PREFIX = "21";
  
  /**
   * Executor that uses virtual threads when available, falling back to a platform thread pool
   * when virtual threads are not supported.
   */
  private final Executor virtualThreadExecutor;
  
  /**
   * Constructor that initializes the virtual thread executor.
   */
  public ThreadPinningDetector() {
    this.virtualThreadExecutor = createVirtualThreadExecutorIfSupported();
  }
  
  /**
   * Creates an executor that uses virtual threads if supported, or falls back to a
   * standard thread pool if not.
   *
   * @return an executor service
   */
  @VisibleForTesting
  Executor createVirtualThreadExecutorIfSupported() {
    if (isVirtualThreadsEnabled()) {
      try {
        // Use reflection to avoid direct dependency on Java 21 API
        Class<?> executorsClass = Class.forName("java.util.concurrent.Executors");
        Method newVirtualThreadPerTaskExecutor = 
            executorsClass.getMethod("newVirtualThreadPerTaskExecutor");
        return (Executor) newVirtualThreadPerTaskExecutor.invoke(null);
      }
      catch (Exception e) {
        log.debug("Failed to create virtual thread executor, falling back to platform threads", e);
      }
    }
    
    // Fall back to a standard thread pool
    return ForkJoinPool.commonPool();
  }

  /**
   * Checks if the current JVM supports and has enabled virtual threads.
   *
   * @return true if virtual threads are supported and enabled
   */
  public boolean isVirtualThreadsEnabled() {
    try {
      // Check if running on Java 21 or later
      String javaVersion = System.getProperty(JAVA_VERSION_PROPERTY, "");
      boolean isJava21OrLater = javaVersion.startsWith(JAVA_21_PREFIX) || 
          (javaVersion.compareTo(JAVA_21_PREFIX) > 0);
      
      if (!isJava21OrLater) {
        return false;
      }
      
      // Check if Thread.ofVirtual() method exists (Java 21 feature)
      try {
        Class.forName("java.lang.Thread").getMethod("ofVirtual");
        return true;
      }
      catch (NoSuchMethodException e) {
        return false;
      }
    }
    catch (SecurityException | ClassNotFoundException e) {
      log.debug("Unable to check for virtual threads due to security restrictions", e);
      return false;
    }
  }

  /**
   * Detects potential thread pinning issues in a JAX-RS resource method.
   * <p>
   * This method analyzes the provided resource class and method to identify code patterns
   * that could cause virtual thread pinning.
   *
   * @param resourceClass the JAX-RS resource class
   * @param methodName the name of the method to check
   * @return true if potential pinning issues were detected
   */
  public boolean detectPinningIssues(final Class<?> resourceClass, final String methodName) {
    if (!isVirtualThreadsEnabled()) {
      return false; // No pinning issues if virtual threads aren't enabled
    }

    boolean issuesDetected = false;

    try {
      // Check for synchronized methods
      for (Method method : resourceClass.getDeclaredMethods()) {
        if (method.getName().equals(methodName) && isSynchronized(method)) {
          logSynchronizedMethodWarning(resourceClass.getSimpleName(), methodName);
          issuesDetected = true;
        }
      }

      // Check for synchronized blocks (would require bytecode analysis in a real implementation)
      // This is a simplified check that just looks for common patterns in the method name
      if (methodName.toLowerCase().contains("sync") || methodName.toLowerCase().contains("lock")) {
        log.warn("Thread pinning potential: Method {}.{} may contain synchronized blocks " +
            "that could prevent virtual thread unmounting. Consider using " +
            "java.util.concurrent.locks.ReentrantLock instead.", 
            resourceClass.getSimpleName(), methodName);
        issuesDetected = true;
      }

      // Check for potential blocking I/O operations
      if (methodName.toLowerCase().contains("read") || 
          methodName.toLowerCase().contains("write") ||
          methodName.toLowerCase().contains("load") ||
          methodName.toLowerCase().contains("save") ||
          methodName.toLowerCase().contains("download") ||
          methodName.toLowerCase().contains("upload")) {
        log.info("Thread pinning caution: Method {}.{} may contain blocking I/O operations. " +
            "Ensure these operations are not performed within synchronized blocks to " +
            "avoid virtual thread pinning.", resourceClass.getSimpleName(), methodName);
      }
    }
    catch (Exception e) {
      log.debug("Error while checking for thread pinning issues in {}.{}", 
          resourceClass.getSimpleName(), methodName, e);
    }

    return issuesDetected;
  }

  /**
   * Checks if a method has the synchronized modifier.
   *
   * @param method the method to check
   * @return true if the method is synchronized
   */
  private boolean isSynchronized(final Method method) {
    return (method.getModifiers() & java.lang.reflect.Modifier.SYNCHRONIZED) != 0;
  }

  /**
   * Logs a warning about a synchronized method that could cause thread pinning.
   *
   * @param className the name of the class containing the method
   * @param methodName the name of the synchronized method
   */
  private void logSynchronizedMethodWarning(final String className, final String methodName) {
    log.warn("Thread pinning detected: Synchronized method {}.{} may prevent virtual thread unmounting. " +
        "Consider using java.util.concurrent.locks.ReentrantLock instead of synchronized methods " +
        "to avoid carrier thread blocking.", className, methodName);
  }

  /**
   * Checks if the current thread is a virtual thread.
   * <p>
   * This method uses reflection to avoid direct dependencies on Java 21 APIs,
   * allowing the code to compile and run on earlier Java versions.
   *
   * @return true if the current thread is a virtual thread
   */
  public boolean isVirtualThread() {
    try {
      // Use reflection to call Thread.currentThread().isVirtual()
      Method isVirtualMethod = Thread.class.getMethod("isVirtual");
      return (Boolean) isVirtualMethod.invoke(Thread.currentThread());
    }
    catch (Exception e) {
      // Method doesn't exist (pre-Java 21) or other reflection error
      return false;
    }
  }

  /**
   * Provides a safe alternative to synchronized blocks for use with virtual threads.
   * <p>
   * This method demonstrates how to use ReentrantLock instead of synchronized blocks
   * to avoid thread pinning issues.
   *
   * @param operation the operation to execute within the lock
   * @param lock the ReentrantLock to use (should be a field in the resource class)
   */
  public void executeWithLock(final Runnable operation, final ReentrantLock lock) {
    lock.lock();
    try {
      operation.run();
    }
    finally {
      lock.unlock();
    }
  }
  
  /**
   * Provides a safe alternative to synchronized blocks for use with virtual threads,
   * with support for returning a value.
   *
   * @param <T> the type of the result
   * @param supplier the operation to execute within the lock that returns a value
   * @param lock the ReentrantLock to use (should be a field in the resource class)
   * @return the result of the supplier operation
   */
  public <T> T supplyWithLock(final java.util.function.Supplier<T> supplier, final ReentrantLock lock) {
    lock.lock();
    try {
      return supplier.get();
    }
    finally {
      lock.unlock();
    }
  }
  
  /**
   * Checks if the current execution is happening within a synchronized block or method.
   * <p>
   * This is a best-effort detection and may not catch all cases of synchronization.
   *
   * @return true if the current execution appears to be within a synchronized context
   */
  public boolean isInSynchronizedContext() {
    // This is a simplified implementation that uses stack trace analysis
    // A more robust implementation would use JVM tooling APIs
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stackTrace) {
      // Look for common patterns in synchronized method names
      String methodName = element.getMethodName().toLowerCase();
      if (methodName.contains("synchronized") || 
          methodName.contains("monitor") || 
          methodName.equals("wait") || 
          methodName.equals("notify") || 
          methodName.equals("notifyall")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Detects potential native method calls that could cause thread pinning.
   * <p>
   * Native methods can prevent virtual threads from unmounting from their carrier threads.
   *
   * @param className the name of the class containing native methods
   * @param methodName the name of the method that might call native methods
   */
  public void checkForNativeMethodCalls(final String className, final String methodName) {
    if (!isVirtualThreadsEnabled() || !isVirtualThread()) {
      return; // Only relevant for virtual threads
    }

    log.debug("Checking for native method calls in {}.{} that could cause thread pinning", 
        className, methodName);
    
    // In a real implementation, this would use bytecode analysis or runtime monitoring
    // to detect actual native method calls. This simplified version just provides guidance.
    log.info("Virtual thread executing {}.{}. Be cautious of native method calls that could " +
        "cause thread pinning. Consider dispatching native operations to dedicated platform threads.",
        className, methodName);
  }

  /**
   * Detects blocking I/O operations that aren't properly dispatched to virtual threads.
   * <p>
   * Blocking I/O operations inside synchronized blocks can prevent virtual threads from
   * unmounting from their carrier threads.
   *
   * @param className the name of the class containing the I/O operations
   * @param methodName the name of the method performing I/O
   * @param isInSynchronizedBlock whether the I/O operation is inside a synchronized block
   */
  public void checkForBlockingIO(final String className, final String methodName, final boolean isInSynchronizedBlock) {
    if (!isVirtualThreadsEnabled() || !isVirtualThread()) {
      return; // Only relevant for virtual threads
    }

    if (isInSynchronizedBlock) {
      log.warn("Thread pinning detected: Blocking I/O operation in {}.{} inside a synchronized block " +
          "will prevent virtual thread unmounting. Move I/O operations outside synchronized blocks " +
          "or use java.util.concurrent.locks.ReentrantLock instead.", className, methodName);
    }
    else {
      log.debug("Blocking I/O operation in {}.{} is safe for virtual threads when not in synchronized blocks",
          className, methodName);
    }
  }
  
  /**
   * Enables JVM-level tracing of pinned virtual threads.
   * <p>
   * This method sets the system property that causes the JVM to emit stack traces
   * when a virtual thread blocks while pinned to its carrier thread.
   * <p>
   * Note: This should only be used for debugging and diagnostic purposes, as it can
   * generate a large volume of log output in a busy application.
   *
   * @param enabled whether to enable pinned thread tracing
   */
  public void enablePinnedThreadTracing(final boolean enabled) {
    if (!isVirtualThreadsEnabled()) {
      log.info("Virtual threads not enabled, pinned thread tracing not applicable");
      return;
    }
    
    try {
      if (enabled) {
        System.setProperty("jdk.tracePinnedThreads", "true");
        log.info("Enabled JVM-level tracing of pinned virtual threads. " +
            "Stack traces will be emitted when virtual threads block while pinned.");
      }
      else {
        System.clearProperty("jdk.tracePinnedThreads");
        log.info("Disabled JVM-level tracing of pinned virtual threads.");
      }
    }
    catch (SecurityException e) {
      log.warn("Unable to modify system properties for pinned thread tracing due to security restrictions", e);
    }
  }
  
  /**
   * Executes a task using virtual threads if available, or falls back to platform threads if not.
   * <p>
   * This method provides a convenient way to run tasks on virtual threads without having to
   * directly depend on Java 21 APIs.
   *
   * @param task the task to execute
   */
  public void executeOnVirtualThread(final Runnable task) {
    virtualThreadExecutor.execute(task);
  }
  
  /**
   * Executes a task that returns a result using virtual threads if available.
   * <p>
   * This method provides a convenient way to run tasks on virtual threads without having to
   * directly depend on Java 21 APIs.
   *
   * @param <T> the type of the result
   * @param task the task to execute that returns a result
   * @return a Future representing the pending result
   */
  public <T> Future<T> submitToVirtualThread(final Supplier<T> task) {
    if (virtualThreadExecutor instanceof ExecutorService) {
      return ((ExecutorService) virtualThreadExecutor).submit(task::get);
    }
    else {
      // Fall back to a new executor service if the virtual thread executor doesn't support submit
      ExecutorService executor = Executors.newCachedThreadPool();
      try {
        return executor.submit(task::get);
      }
      finally {
        executor.shutdown();
      }
    }
  }
  
  /**
   * Suggests remediation for a detected thread pinning issue.
   * <p>
   * This method provides detailed guidance on how to fix common thread pinning issues.
   *
   * @param pinningType the type of pinning issue detected ("synchronized", "native", or "io")
   * @return a string containing remediation advice
   */
  public String getRemediationAdvice(final String pinningType) {
    switch (pinningType.toLowerCase()) {
      case "synchronized":
        return "Replace synchronized blocks/methods with java.util.concurrent.locks.ReentrantLock. " +
            "Example:\n" +
            "private final ReentrantLock lock = new ReentrantLock();\n" +
            "public void method() {\n" +
            "  lock.lock();\n" +
            "  try {\n" +
            "    // critical section\n" +
            "  } finally {\n" +
            "    lock.unlock();\n" +
            "  }\n" +
            "}";
      case "native":
        return "Dispatch native method calls to dedicated platform threads using an ExecutorService. " +
            "Example:\n" +
            "ExecutorService platformExecutor = Executors.newFixedThreadPool(4);\n" +
            "CompletableFuture.supplyAsync(() -> callNativeMethod(), platformExecutor);";
      case "io":
        return "Ensure blocking I/O operations are not performed within synchronized blocks. " +
            "Move I/O operations outside of synchronized blocks, or use ReentrantLock instead. " +
            "Consider using non-blocking I/O APIs where available.";
      default:
        return "Review code for synchronized blocks/methods, native method calls, and blocking I/O " +
            "operations that could cause virtual thread pinning. Replace synchronized with " +
            "java.util.concurrent.locks.ReentrantLock and ensure blocking operations are not " +
            "performed within synchronized contexts.";
    }
  }
}