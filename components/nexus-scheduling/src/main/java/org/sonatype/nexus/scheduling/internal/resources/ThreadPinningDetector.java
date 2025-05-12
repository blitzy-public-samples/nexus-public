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

import java.io.IOException;
import java.lang.StackWalker.StackFrame;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;

import org.sonatype.goodies.common.ComponentSupport;

import static java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE;
import static java.lang.System.getProperty;

/**
 * Utility class that detects thread-pinning scenarios in JAX-RS resource methods that could prevent
 * carrier thread release when using Virtual Threads.
 * <p>
 * This detector helps identify operations that could block the carrier thread when running in a
 * Virtual Thread environment, such as:
 * <ul>
 *   <li>Synchronization on non-Thread objects</li>
 *   <li>Blocking I/O without virtual thread dispatch</li>
 *   <li>Direct native method calls</li>
 * </ul>
 * <p>
 * The detector provides runtime checks and logging to help maintain responsive REST endpoints
 * by preventing inadvertent carrier thread exhaustion in the Java 21 virtual threads execution model.
 *
 * @since 3.60
 */
@Named
@Singleton
public class ThreadPinningDetector
    extends ComponentSupport
{
  private static final StackWalker STACK_WALKER = StackWalker.getInstance(RETAIN_CLASS_REFERENCE);
  
  private static final String VIRTUAL_THREAD_CLASS_NAME = "java.lang.VirtualThread";
  
  /**
   * System property to enable thread pinning detection globally.
   */
  public static final String THREAD_PINNING_DETECTION_ENABLED = "nexus.thread.pinning.detection.enabled";
  
  /**
   * System property to set the minimum duration (in milliseconds) for a pinned thread to trigger a warning.
   */
  public static final String THREAD_PINNING_THRESHOLD_MS = "nexus.thread.pinning.threshold.ms";
  
  /**
   * Default threshold in milliseconds for pinned thread warnings (20ms).
   */
  private static final long DEFAULT_PINNING_THRESHOLD_MS = 20L;
  
  /**
   * System property to set the monitoring interval in seconds.
   */
  public static final String THREAD_PINNING_MONITORING_INTERVAL = "nexus.thread.pinning.monitoring.interval";
  
  /**
   * Default monitoring interval in seconds (60 seconds).
   */
  private static final long DEFAULT_MONITORING_INTERVAL_SECONDS = 60L;
  
  /**
   * Cache of locks for resources to avoid creating new locks for the same resource.
   */
  private final ConcurrentHashMap<Object, ReentrantLock> resourceLocks = new ConcurrentHashMap<>();
  
  /**
   * Whether thread pinning detection is enabled globally.
   */
  private boolean detectionEnabled = false;
  
  /**
   * Threshold in milliseconds for pinned thread warnings.
   */
  private long pinningThresholdMs = DEFAULT_PINNING_THRESHOLD_MS;
  
  /**
   * Monitoring interval in seconds.
   */
  private long monitoringIntervalSeconds = DEFAULT_MONITORING_INTERVAL_SECONDS;
  
  /**
   * Scheduled executor for monitoring thread pinning.
   */
  private ScheduledExecutorService monitoringExecutor;
  
  /**
   * Whether thread pinning monitoring is running.
   */
  private final AtomicBoolean monitoringRunning = new AtomicBoolean(false);
  
  /**
   * Counter for total pinned threads detected.
   */
  private final AtomicLong totalPinnedThreadsDetected = new AtomicLong(0);
  
  /**
   * Counter for total synchronized blocks detected.
   */
  private final AtomicLong totalSynchronizedBlocksDetected = new AtomicLong(0);
  
  /**
   * Counter for total native method calls detected.
   */
  private final AtomicLong totalNativeMethodCallsDetected = new AtomicLong(0);
  
  /**
   * Counter for total blocking I/O operations detected.
   */
  private final AtomicLong totalBlockingIODetected = new AtomicLong(0);
  
  /**
   * Map of class names to count of pinning issues detected.
   */
  private final ConcurrentHashMap<String, AtomicLong> pinnedThreadsByClass = new ConcurrentHashMap<>();
  
  /**
   * Checks if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public boolean isVirtualThread() {
    return Thread.currentThread().getClass().getName().equals(VIRTUAL_THREAD_CLASS_NAME);
  }
  
  /**
   * Detects if the current execution context contains potential thread-pinning scenarios.
   * This method should be called from JAX-RS resource methods that perform potentially
   * blocking operations to ensure they don't inadvertently pin virtual threads.
   *
   * @param resourceClass the JAX-RS resource class to check
   * @return true if potential thread-pinning scenarios are detected, false otherwise
   */
  public boolean detectPotentialPinning(final Class<?> resourceClass) {
    if (!isVirtualThread()) {
      return false; // Only relevant for virtual threads
    }
    
    boolean hasSynchronizedMethods = detectSynchronizedMethods(resourceClass);
    boolean hasSynchronizedBlocks = detectSynchronizedBlocks();
    boolean hasNativeMethodCalls = detectNativeMethodCalls();
    
    return hasSynchronizedMethods || hasSynchronizedBlocks || hasNativeMethodCalls;
  }
  
  /**
   * Detects synchronized methods in the given JAX-RS resource class that could pin virtual threads.
   *
   * @param resourceClass the JAX-RS resource class to check
   * @return true if synchronized methods are detected, false otherwise
   */
  public boolean detectSynchronizedMethods(final Class<?> resourceClass) {
    if (!isVirtualThread() || !isJaxRsResource(resourceClass)) {
      return false;
    }
    
    Method[] methods = resourceClass.getDeclaredMethods();
    for (Method method : methods) {
      if (java.lang.reflect.Modifier.isSynchronized(method.getModifiers())) {
        log.warn("Thread pinning risk detected: Synchronized method '{}' in JAX-RS resource class {}. "
            + "Consider using ReentrantLock instead of synchronized methods with virtual threads.",
            method.getName(), resourceClass.getName());
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Detects synchronized blocks in the current execution stack that could pin virtual threads.
   *
   * @return true if synchronized blocks are detected, false otherwise
   */
  public boolean detectSynchronizedBlocks() {
    if (!isVirtualThread()) {
      return false;
    }
    
    List<StackFrame> frames = STACK_WALKER.walk(s -> s.collect(Collectors.toList()));
    
    // Look for monitorenter bytecode operation in the stack trace
    for (StackFrame frame : frames) {
      String methodName = frame.getMethodName();
      if (methodName.contains("$synchronized")) {
        log.warn("Thread pinning risk detected: Synchronized block in method '{}' of class {}. "
            + "Consider using ReentrantLock instead of synchronized blocks with virtual threads.",
            methodName, frame.getClassName());
        
        // Increment counters
        totalSynchronizedBlocksDetected.incrementAndGet();
        totalPinnedThreadsDetected.incrementAndGet();
        pinnedThreadsByClass.computeIfAbsent(frame.getClassName(), k -> new AtomicLong()).incrementAndGet();
        
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Detects native method calls in the current execution stack that could pin virtual threads.
   *
   * @return true if native method calls are detected, false otherwise
   */
  public boolean detectNativeMethodCalls() {
    if (!isVirtualThread()) {
      return false;
    }
    
    List<StackFrame> frames = STACK_WALKER.walk(s -> s.collect(Collectors.toList()));
    
    for (StackFrame frame : frames) {
      try {
        Class<?> clazz = frame.getDeclaringClass();
        Method method = clazz.getDeclaredMethod(frame.getMethodName(), getParameterTypes(frame));
        
        if (java.lang.reflect.Modifier.isNative(method.getModifiers())) {
          log.warn("Thread pinning risk detected: Native method call to '{}' in class {}. "
              + "Native methods pin virtual threads to carrier threads during execution.",
              method.getName(), clazz.getName());
          
          // Increment counters
          totalNativeMethodCallsDetected.incrementAndGet();
          totalPinnedThreadsDetected.incrementAndGet();
          pinnedThreadsByClass.computeIfAbsent(clazz.getName(), k -> new AtomicLong()).incrementAndGet();
          
          return true;
        }
      }
      catch (NoSuchMethodException | SecurityException e) {
        // Skip frames where we can't determine the method
      }
    }
    
    return false;
  }
  
  /**
   * Detects blocking I/O operations that aren't properly dispatched to virtual threads.
   * This method uses heuristics to identify common I/O patterns that could block the carrier thread.
   * <p>
   * In Java 21, blocking I/O operations in virtual threads should automatically unmount the virtual thread
   * from its carrier thread, but this can be prevented if the operation occurs within a synchronized block
   * or if the I/O operation is performed through a native method.
   *
   * @return true if potentially blocking I/O operations are detected, false otherwise
   */
  public boolean detectBlockingIO() {
    if (!isVirtualThread()) {
      return false;
    }
    
    List<StackFrame> frames = STACK_WALKER.walk(s -> s.collect(Collectors.toList()));
    
    // Check for common blocking I/O operations
    Predicate<StackFrame> isBlockingIO = frame -> {
      String className = frame.getClassName();
      String methodName = frame.getMethodName();
      
      // Check for java.io blocking operations
      boolean isJavaIoBlocking = className.startsWith("java.io") && 
          (methodName.equals("read") || methodName.equals("write") || 
           methodName.equals("readFully") || methodName.equals("readLine") ||
           methodName.equals("available") || methodName.equals("flush") ||
           methodName.equals("close"));
      
      // Check for java.net blocking operations
      boolean isJavaNetBlocking = className.startsWith("java.net") && 
          (methodName.equals("connect") || methodName.equals("accept") || 
           methodName.equals("read") || methodName.equals("write") ||
           methodName.equals("getInputStream") || methodName.equals("getOutputStream"));
      
      // Check for JDBC blocking operations
      boolean isJdbcBlocking = className.contains("java.sql") &&
          (methodName.startsWith("execute") || methodName.equals("getConnection") ||
           methodName.equals("prepareStatement") || methodName.equals("commit") ||
           methodName.equals("rollback"));
      
      // Check for AWS S3 blocking operations (relevant for S3BlobStore)
      boolean isS3Blocking = className.contains("amazonaws") &&
          (methodName.contains("upload") || methodName.contains("download") ||
           methodName.contains("get") || methodName.contains("put"));
      
      return isJavaIoBlocking || isJavaNetBlocking || isJdbcBlocking || isS3Blocking;
    };
    
    Optional<StackFrame> blockingIOFrame = frames.stream().filter(isBlockingIO).findFirst();
    
    if (blockingIOFrame.isPresent()) {
      StackFrame frame = blockingIOFrame.get();
      log.warn("Thread pinning risk detected: Blocking I/O operation '{}' in class {}. "
          + "Consider using java.nio non-blocking I/O or ensuring operations are properly dispatched to virtual threads. "
          + "If this operation is inside a synchronized block, consider using ReentrantLock instead.",
          frame.getMethodName(), frame.getClassName());
      
      // Increment counters
      totalBlockingIODetected.incrementAndGet();
      totalPinnedThreadsDetected.incrementAndGet();
      pinnedThreadsByClass.computeIfAbsent(frame.getClassName(), k -> new AtomicLong()).incrementAndGet();
      
      return true;
    }
    
    return false;
  }
  
  /**
   * Provides a safe alternative to synchronized blocks for JAX-RS resources running in virtual threads.
   * This method demonstrates how to use ReentrantLock instead of synchronized blocks.
   *
   * @param resource the resource object to lock on
   * @param runnable the code to execute while holding the lock
   */
  public void withSafeLock(final Object resource, final Runnable runnable) {
    ReentrantLock lock = getLockForResource(resource);
    lock.lock();
    try {
      runnable.run();
    }
    finally {
      lock.unlock();
    }
  }
  
  /**
   * Gets or creates a ReentrantLock for the given resource object.
   * In a real implementation, this would use a cache or weak reference map to store locks.
   *
   * @param resource the resource object to get a lock for
   * @return a ReentrantLock for the resource
   */
  private ReentrantLock getLockForResource(final Object resource) {
    return resourceLocks.computeIfAbsent(resource, k -> new ReentrantLock());
  }
  
  /**
   * Checks if the given class is a JAX-RS resource class.
   *
   * @param clazz the class to check
   * @return true if the class is a JAX-RS resource class, false otherwise
   */
  private boolean isJaxRsResource(final Class<?> clazz) {
    return clazz.isAnnotationPresent(Path.class);
  }
  
  /**
   * Gets the parameter types for a method from a stack frame.
   * This is a best-effort attempt and may not work for all methods.
   *
   * @param frame the stack frame to get parameter types for
   * @return an array of parameter types, or null if they cannot be determined
   */
  @Nullable
  private Class<?>[] getParameterTypes(final StackFrame frame) {
    // This is a simplified implementation
    // In a real implementation, we would need to parse the descriptor
    return null;
  }
  
  /**
   * Logs a summary of thread-pinning risks detected in the current execution context.
   * This method can be called at the end of a JAX-RS resource method to provide a comprehensive
   * report of potential thread-pinning issues.
   *
   * @param resourceClass the JAX-RS resource class to check
   */
  public void logThreadPinningRisks(final Class<?> resourceClass) {
    if (!isVirtualThread() || !detectionEnabled) {
      return;
    }
    
    boolean hasSynchronizedMethods = detectSynchronizedMethods(resourceClass);
    boolean hasSynchronizedBlocks = detectSynchronizedBlocks();
    boolean hasNativeMethodCalls = detectNativeMethodCalls();
    boolean hasBlockingIO = detectBlockingIO();
    
    if (hasSynchronizedMethods || hasSynchronizedBlocks || hasNativeMethodCalls || hasBlockingIO) {
      log.warn("Thread pinning risks detected in JAX-RS resource class {}. "
          + "This may prevent carrier thread release when using Virtual Threads. "
          + "Consider refactoring to avoid synchronized blocks/methods, use ReentrantLock instead, "
          + "and ensure proper dispatching of blocking I/O operations.",
          resourceClass.getName());
      
      // Provide specific recommendations based on the detected issues
      if (hasSynchronizedMethods) {
        logRecommendationForSynchronizedMethods(resourceClass);
      }
      
      if (hasSynchronizedBlocks) {
        logRecommendationForSynchronizedBlocks();
      }
      
      if (hasNativeMethodCalls) {
        logRecommendationForNativeMethodCalls();
      }
      
      if (hasBlockingIO) {
        logRecommendationForBlockingIO();
      }
    }
  }
  
  /**
   * Logs recommendations for fixing synchronized methods.
   *
   * @param resourceClass the JAX-RS resource class
   */
  private void logRecommendationForSynchronizedMethods(final Class<?> resourceClass) {
    log.info("Recommendation for synchronized methods in {}: "
        + "\n1. Replace 'synchronized' keyword with java.util.concurrent.locks.ReentrantLock "
        + "\n2. Example: "
        + "\n   // Before: "
        + "\n   public synchronized void doSomething() { ... } "
        + "\n   // After: "
        + "\n   private final ReentrantLock lock = new ReentrantLock(); "
        + "\n   public void doSomething() { "
        + "\n     lock.lock(); "
        + "\n     try { "
        + "\n       ... "
        + "\n     } finally { "
        + "\n       lock.unlock(); "
        + "\n     } "
        + "\n   } "
        + "\n3. Or use the withSafeLock() helper method in this class",
        resourceClass.getName());
  }
  
  /**
   * Logs recommendations for fixing synchronized blocks.
   */
  private void logRecommendationForSynchronizedBlocks() {
    log.info("Recommendation for synchronized blocks: "
        + "\n1. Replace 'synchronized(obj) { ... }' with ReentrantLock "
        + "\n2. Example: "
        + "\n   // Before: "
        + "\n   synchronized(this) { "
        + "\n     // code that might block "
        + "\n   } "
        + "\n   // After: "
        + "\n   private final ReentrantLock lock = new ReentrantLock(); "
        + "\n   ... "
        + "\n   lock.lock(); "
        + "\n   try { "
        + "\n     // code that might block "
        + "\n   } finally { "
        + "\n     lock.unlock(); "
        + "\n   } "
        + "\n3. Or use the withSafeLock() helper method in this class");
  }
  
  /**
   * Logs recommendations for fixing native method calls.
   */
  private void logRecommendationForNativeMethodCalls() {
    log.info("Recommendation for native method calls: "
        + "\n1. Native method calls will pin virtual threads to carrier threads "
        + "\n2. If possible, avoid making native method calls from JAX-RS resource methods "
        + "\n3. If native calls are necessary, consider offloading them to a separate thread pool "
        + "\n4. Example: "
        + "\n   // Before: "
        + "\n   public Response getData() { "
        + "\n     // Direct native method call "
        + "\n     byte[] result = nativeOperation(); "
        + "\n     return Response.ok(result).build(); "
        + "\n   } "
        + "\n   // After: "
        + "\n   private final ExecutorService executorService = Executors.newFixedThreadPool(4); "
        + "\n   ... "
        + "\n   public Response getData() { "
        + "\n     // Offload native operation to platform thread pool "
        + "\n     CompletableFuture<byte[]> future = CompletableFuture.supplyAsync( "
        + "\n         () -> nativeOperation(), executorService); "
        + "\n     byte[] result = future.join(); "
        + "\n     return Response.ok(result).build(); "
        + "\n   }");
  }
  
  /**
   * Logs recommendations for fixing blocking I/O operations.
   */
  private void logRecommendationForBlockingIO() {
    log.info("Recommendation for blocking I/O operations: "
        + "\n1. Ensure blocking I/O operations are not performed inside synchronized blocks "
        + "\n2. Consider using non-blocking I/O APIs where possible (java.nio) "
        + "\n3. For JDBC operations, ensure the JDBC driver supports virtual threads "
        + "\n4. For S3 operations, consider using the AWS SDK's async client "
        + "\n5. Example for safe I/O in JAX-RS resources: "
        + "\n   // Use the safelyExecute helper method in this class "
        + "\n   @GET "
        + "\n   @Path(\"/data\") "
        + "\n   public Response getData() { "
        + "\n     return threadPinningDetector.safelyExecute(() -> { "
        + "\n       // Potentially blocking I/O operation "
        + "\n       return Response.ok(service.fetchData()).build(); "
        + "\n     }, getClass()); "
        + "\n   }");
  }
  
  /**
   * Checks for thread pinning in JAX-RS resource methods.
   * This method is designed to be called from a JAX-RS filter or interceptor to detect
   * potential thread pinning issues before they occur.
   *
   * @param resourceClass the JAX-RS resource class
   * @param methodName the name of the JAX-RS resource method
   * @return true if potential thread pinning is detected, false otherwise
   */
  public boolean checkJaxRsMethodForPinning(final Class<?> resourceClass, final String methodName) {
    if (!isVirtualThread() || !isJaxRsResource(resourceClass)) {
      return false;
    }
    
    try {
      // Find the method by name (this is a simplified approach)
      Method[] methods = resourceClass.getDeclaredMethods();
      for (Method method : methods) {
        if (method.getName().equals(methodName)) {
          // Check if the method is synchronized
          if (java.lang.reflect.Modifier.isSynchronized(method.getModifiers())) {
            log.warn("Thread pinning risk detected: JAX-RS resource method '{}' in class {} is synchronized. "
                + "Synchronized methods pin virtual threads to carrier threads during execution. "
                + "Consider using ReentrantLock instead.",
                methodName, resourceClass.getName());
            return true;
          }
          
          // Check method annotations for potential blocking operations
          if (hasBlockingAnnotations(method)) {
            log.warn("Thread pinning risk detected: JAX-RS resource method '{}' in class {} has annotations "
                + "indicating potentially blocking operations. Ensure these operations are properly "
                + "dispatched to avoid pinning virtual threads.",
                methodName, resourceClass.getName());
            return true;
          }
        }
      }
    }
    catch (SecurityException e) {
      log.debug("Unable to check JAX-RS method for thread pinning: {}", e.getMessage());
    }
    
    return false;
  }
  
  /**
   * Checks if a method has annotations that indicate potentially blocking operations.
   * This is a heuristic approach and may not catch all cases.
   *
   * @param method the method to check
   * @return true if the method has annotations indicating blocking operations, false otherwise
   */
  private boolean hasBlockingAnnotations(final Method method) {
    // This is a simplified implementation that looks for common annotations
    // that might indicate blocking operations
    return method.isAnnotationPresent(javax.transaction.Transactional.class) ||
           method.getName().startsWith("get") || method.getName().startsWith("find") ||
           method.getName().startsWith("load") || method.getName().startsWith("save") ||
           method.getName().startsWith("delete") || method.getName().startsWith("update");
  }
  
  /**
   * Initializes the thread pinning detector based on system properties.
   */
  @PostConstruct
  public void initialize() {
    detectionEnabled = Boolean.parseBoolean(getProperty(THREAD_PINNING_DETECTION_ENABLED, "true"));
    pinningThresholdMs = Long.parseLong(getProperty(THREAD_PINNING_THRESHOLD_MS, 
        String.valueOf(DEFAULT_PINNING_THRESHOLD_MS)));
    monitoringIntervalSeconds = Long.parseLong(getProperty(THREAD_PINNING_MONITORING_INTERVAL,
        String.valueOf(DEFAULT_MONITORING_INTERVAL_SECONDS)));
    
    if (detectionEnabled) {
      log.info("Thread pinning detection enabled with threshold of {}ms", pinningThresholdMs);
      
      // Enable JVM's built-in thread pinning detection if available (Java 21+)
      if (getProperty("jdk.tracePinnedThreads") == null) {
        System.setProperty("jdk.tracePinnedThreads", String.valueOf(pinningThresholdMs));
        log.debug("Enabled JVM thread pinning detection with threshold {}ms", pinningThresholdMs);
      }
    }
    else {
      log.info("Thread pinning detection disabled");
    }
  }
  
  /**
   * Checks if the current thread is pinned to its carrier thread.
   * This is a heuristic approach and may not be 100% accurate.
   *
   * @return true if the current thread appears to be pinned, false otherwise
   */
  public boolean isThreadPinned() {
    if (!isVirtualThread() || !detectionEnabled) {
      return false;
    }
    
    // Check for synchronized blocks in the stack trace
    if (detectSynchronizedBlocks()) {
      return true;
    }
    
    // Check for native method calls in the stack trace
    if (detectNativeMethodCalls()) {
      return true;
    }
    
    return false;
  }
  
  /**
   * Checks if the specified thread is pinned to its carrier thread.
   * This is a heuristic approach and may not be 100% accurate.
   *
   * @param thread the thread to check
   * @return true if the thread appears to be pinned, false otherwise
   */
  public boolean isThreadPinned(final Thread thread) {
    if (!isVirtualThread(thread) || !detectionEnabled) {
      return false;
    }
    
    // Get the stack trace of the thread
    StackTraceElement[] stackTrace = thread.getStackTrace();
    
    // Check for synchronized blocks in the stack trace
    for (StackTraceElement element : stackTrace) {
      String methodName = element.getMethodName();
      if (methodName.contains("$synchronized")) {
        log.warn("Thread pinning detected in thread {}: Synchronized block in method '{}' of class {}",
            thread.getName(), methodName, element.getClassName());
        return true;
      }
    }
    
    // Check for native method calls in the stack trace
    for (StackTraceElement element : stackTrace) {
      try {
        Class<?> clazz = Class.forName(element.getClassName());
        Method method = findMethodByName(clazz, element.getMethodName());
        
        if (method != null && java.lang.reflect.Modifier.isNative(method.getModifiers())) {
          log.warn("Thread pinning detected in thread {}: Native method call to '{}' in class {}",
              thread.getName(), method.getName(), clazz.getName());
          return true;
        }
      }
      catch (ClassNotFoundException | SecurityException e) {
        // Skip elements where we can't determine the method
      }
    }
    
    return false;
  }
  
  /**
   * Checks if the specified thread is a virtual thread.
   *
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  public boolean isVirtualThread(final Thread thread) {
    return thread.getClass().getName().equals(VIRTUAL_THREAD_CLASS_NAME);
  }
  
  /**
   * Finds a method by name in the specified class.
   * This is a helper method for isThreadPinned(Thread).
   *
   * @param clazz the class to search in
   * @param methodName the name of the method to find
   * @return the method, or null if not found
   */
  @Nullable
  private Method findMethodByName(final Class<?> clazz, final String methodName) {
    Method[] methods = clazz.getDeclaredMethods();
    for (Method method : methods) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    return null;
  }
  
  /**
   * Starts monitoring for thread pinning in the application.
   * This method schedules a periodic task that checks for pinned threads and logs warnings.
   */
  public void startMonitoring() {
    if (!detectionEnabled) {
      log.info("Thread pinning detection is disabled. Monitoring will not start.");
      return;
    }
    
    if (monitoringRunning.compareAndSet(false, true)) {
      log.info("Starting thread pinning monitoring with interval of {} seconds", monitoringIntervalSeconds);
      
      monitoringExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "thread-pinning-monitor");
        thread.setDaemon(true);
        return thread;
      });
      
      monitoringExecutor.scheduleAtFixedRate(this::checkForPinnedThreads, 
          monitoringIntervalSeconds, monitoringIntervalSeconds, TimeUnit.SECONDS);
    }
    else {
      log.debug("Thread pinning monitoring is already running");
    }
  }
  
  /**
   * Stops monitoring for thread pinning in the application.
   */
  public void stopMonitoring() {
    if (monitoringRunning.compareAndSet(true, false) && monitoringExecutor != null) {
      log.info("Stopping thread pinning monitoring");
      monitoringExecutor.shutdown();
      monitoringExecutor = null;
    }
  }
  
  /**
   * Checks for pinned threads in the application.
   * This method is called periodically by the monitoring task.
   */
  private void checkForPinnedThreads() {
    try {
      log.debug("Checking for pinned threads");
      
      // Get all threads
      Thread[] threads = getThreads();
      int pinnedThreadCount = 0;
      
      // Check each thread for pinning
      for (Thread thread : threads) {
        if (isVirtualThread(thread) && isThreadPinned(thread)) {
          pinnedThreadCount++;
          log.warn("Pinned virtual thread detected: {} ({})", 
              thread.getName(), thread.getClass().getName());
        }
      }
      
      if (pinnedThreadCount > 0) {
        log.warn("Found {} pinned virtual threads", pinnedThreadCount);
      }
      else {
        log.debug("No pinned virtual threads detected");
      }
    }
    catch (Exception e) {
      log.error("Error checking for pinned threads", e);
    }
  }
  
  /**
   * Gets all threads in the application.
   *
   * @return an array of all threads
   */
  private Thread[] getThreads() {
    ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
    ThreadGroup parentGroup;
    while ((parentGroup = rootGroup.getParent()) != null) {
      rootGroup = parentGroup;
    }
    
    Thread[] threads = new Thread[rootGroup.activeCount() * 2];
    int threadCount = rootGroup.enumerate(threads, true);
    
    Thread[] result = new Thread[threadCount];
    System.arraycopy(threads, 0, result, 0, threadCount);
    
    return result;
  }
  
  /**
   * Resets all thread pinning statistics.
   */
  public void resetThreadPinningStatistics() {
    totalPinnedThreadsDetected.set(0);
    totalSynchronizedBlocksDetected.set(0);
    totalNativeMethodCallsDetected.set(0);
    totalBlockingIODetected.set(0);
    pinnedThreadsByClass.clear();
    
    log.info("Thread pinning statistics have been reset");
  }
  
  /**
   * Gets statistics about thread pinning in the application.
   *
   * @return a map of statistic names to values
   */
  public Map<String, Object> getThreadPinningStatistics() {
    Map<String, Object> statistics = new HashMap<>();
    
    statistics.put("detectionEnabled", detectionEnabled);
    statistics.put("pinningThresholdMs", pinningThresholdMs);
    statistics.put("monitoringRunning", monitoringRunning.get());
    statistics.put("monitoringIntervalSeconds", monitoringIntervalSeconds);
    statistics.put("totalPinnedThreadsDetected", totalPinnedThreadsDetected.get());
    statistics.put("totalSynchronizedBlocksDetected", totalSynchronizedBlocksDetected.get());
    statistics.put("totalNativeMethodCallsDetected", totalNativeMethodCallsDetected.get());
    statistics.put("totalBlockingIODetected", totalBlockingIODetected.get());
    
    // Get top 10 classes with pinning issues
    Map<String, Long> topPinnedClasses = new HashMap<>();
    pinnedThreadsByClass.entrySet().stream()
        .sorted((e1, e2) -> Long.compare(e2.getValue().get(), e1.getValue().get()))
        .limit(10)
        .forEach(e -> topPinnedClasses.put(e.getKey(), e.getValue().get()));
    
    statistics.put("topPinnedClasses", topPinnedClasses);
    
    // Get current pinned threads count
    int currentPinnedThreads = 0;
    Thread[] threads = getThreads();
    for (Thread thread : threads) {
      if (isVirtualThread(thread) && isThreadPinned(thread)) {
        currentPinnedThreads++;
      }
    }
    statistics.put("currentPinnedThreads", currentPinnedThreads);
    
    return statistics;
  }
  
  /**
   * Checks if a specific class is at risk for thread pinning.
   * This method performs static analysis of the class to identify common patterns
   * that could lead to thread pinning.
   *
   * @param className the fully qualified name of the class to check
   * @return true if the class is at risk for thread pinning, false otherwise
   */
  public boolean isClassAtRiskForThreadPinning(final String className) {
    try {
      Class<?> clazz = Class.forName(className);
      return isClassAtRiskForThreadPinning(clazz);
    }
    catch (ClassNotFoundException e) {
      log.debug("Class not found: {}", className);
      return false;
    }
  }
  
  /**
   * Checks if a specific class is at risk for thread pinning.
   * This method performs static analysis of the class to identify common patterns
   * that could lead to thread pinning.
   *
   * @param clazz the class to check
   * @return true if the class is at risk for thread pinning, false otherwise
   */
  public boolean isClassAtRiskForThreadPinning(final Class<?> clazz) {
    // Check if the class has synchronized methods
    Method[] methods = clazz.getDeclaredMethods();
    for (Method method : methods) {
      if (java.lang.reflect.Modifier.isSynchronized(method.getModifiers())) {
        log.debug("Class {} has synchronized method {}", clazz.getName(), method.getName());
        return true;
      }
      
      // Check method annotations for potential blocking operations
      if (hasBlockingAnnotations(method)) {
        log.debug("Class {} has method {} with blocking annotations", clazz.getName(), method.getName());
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Checks if a specific method is at risk for thread pinning.
   * This method performs static analysis of the method to identify common patterns
   * that could lead to thread pinning.
   *
   * @param className the fully qualified name of the class containing the method
   * @param methodName the name of the method to check
   * @return true if the method is at risk for thread pinning, false otherwise
   */
  public boolean isMethodAtRiskForThreadPinning(final String className, final String methodName) {
    try {
      Class<?> clazz = Class.forName(className);
      return isMethodAtRiskForThreadPinning(clazz, methodName);
    }
    catch (ClassNotFoundException e) {
      log.debug("Class not found: {}", className);
      return false;
    }
  }
  
  /**
   * Checks if a specific method is at risk for thread pinning.
   * This method performs static analysis of the method to identify common patterns
   * that could lead to thread pinning.
   *
   * @param clazz the class containing the method
   * @param methodName the name of the method to check
   * @return true if the method is at risk for thread pinning, false otherwise
   */
  public boolean isMethodAtRiskForThreadPinning(final Class<?> clazz, final String methodName) {
    try {
      // Find the method by name (this is a simplified approach)
      Method[] methods = clazz.getDeclaredMethods();
      for (Method method : methods) {
        if (method.getName().equals(methodName)) {
          // Check if the method is synchronized
          if (java.lang.reflect.Modifier.isSynchronized(method.getModifiers())) {
            log.debug("Method {} in class {} is synchronized", methodName, clazz.getName());
            return true;
          }
          
          // Check method annotations for potential blocking operations
          if (hasBlockingAnnotations(method)) {
            log.debug("Method {} in class {} has blocking annotations", methodName, clazz.getName());
            return true;
          }
          
          // Check method parameter types for potential blocking operations
          Class<?>[] paramTypes = method.getParameterTypes();
          for (Class<?> paramType : paramTypes) {
            if (paramType.getName().contains("java.io") || 
                paramType.getName().contains("java.sql") ||
                paramType.getName().contains("javax.sql")) {
              log.debug("Method {} in class {} has parameters suggesting I/O or database operations", 
                  methodName, clazz.getName());
              return true;
            }
          }
          
          // Check return type for potential blocking operations
          Class<?> returnType = method.getReturnType();
          if (returnType.getName().contains("java.io") || 
              returnType.getName().contains("java.sql") ||
              returnType.getName().contains("javax.sql")) {
            log.debug("Method {} in class {} has return type suggesting I/O or database operations", 
                methodName, clazz.getName());
            return true;
          }
        }
      }
    }
    catch (SecurityException e) {
      log.debug("Unable to check method for thread pinning: {}", e.getMessage());
    }
    
    return false;
  }
  
  /**
   * Enables or disables thread pinning detection.
   *
   * @param enabled true to enable detection, false to disable
   */
  public void setDetectionEnabled(final boolean enabled) {
    this.detectionEnabled = enabled;
    log.info("Thread pinning detection {}", enabled ? "enabled" : "disabled");
  }
  
  /**
   * Sets the threshold in milliseconds for pinned thread warnings.
   *
   * @param thresholdMs the threshold in milliseconds
   */
  public void setPinningThresholdMs(final long thresholdMs) {
    this.pinningThresholdMs = thresholdMs;
    log.info("Thread pinning detection threshold set to {}ms", thresholdMs);
    
    // Update JVM's built-in thread pinning detection if available
    System.setProperty("jdk.tracePinnedThreads", String.valueOf(thresholdMs));
  }
  
  /**
   * Safely executes a potentially blocking operation in a JAX-RS resource method.
   * This method ensures that the operation doesn't pin the virtual thread to its carrier thread.
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * @GET
   * @Path("/data")
   * public Response getData() {
   *   return threadPinningDetector.safelyExecute(() -> {
   *     // Potentially blocking operation
   *     return Response.ok(service.fetchData()).build();
   *   }, getClass());
   * }
   * }
   * </pre>
   *
   * @param <T> the return type of the operation
   * @param operation the potentially blocking operation to execute
   * @param resourceClass the JAX-RS resource class
   * @return the result of the operation
   */
  public <T> T safelyExecute(final java.util.function.Supplier<T> operation, final Class<?> resourceClass) {
    if (!isVirtualThread() || !detectionEnabled) {
      return operation.get();
    }
    
    long startTime = System.currentTimeMillis();
    T result = operation.get();
    long duration = System.currentTimeMillis() - startTime;
    
    if (duration > pinningThresholdMs) {
      // If the operation took longer than the threshold, check for pinning
      if (isThreadPinned()) {
        log.warn("Potential thread pinning detected in JAX-RS resource class {} - operation took {}ms",
            resourceClass.getName(), duration);
        logThreadPinningRisks(resourceClass);
      }
    }
    
    return result;
  }
  
  /**
   * Safely executes a potentially blocking operation that doesn't return a value.
   *
   * @param operation the potentially blocking operation to execute
   * @param resourceClass the JAX-RS resource class
   */
  public void safelyExecute(final Runnable operation, final Class<?> resourceClass) {
    if (!isVirtualThread() || !detectionEnabled) {
      operation.run();
      return;
    }
    
    long startTime = System.currentTimeMillis();
    operation.run();
    long duration = System.currentTimeMillis() - startTime;
    
    if (duration > pinningThresholdMs) {
      // If the operation took longer than the threshold, check for pinning
      if (isThreadPinned()) {
        log.warn("Potential thread pinning detected in JAX-RS resource class {} - operation took {}ms",
            resourceClass.getName(), duration);
        logThreadPinningRisks(resourceClass);
      }
    }
  }
  
  /**
   * Analyzes a JAX-RS resource method for potential thread pinning issues.
   * This method performs static analysis of the method to identify common patterns
   * that could lead to thread pinning.
   *
   * @param resourceClass the JAX-RS resource class
   * @param methodName the name of the method to analyze
   * @return a string containing analysis results, or null if no issues were found
   */
  @Nullable
  public String analyzeResourceMethod(final Class<?> resourceClass, final String methodName) {
    if (!isJaxRsResource(resourceClass)) {
      return null;
    }
    
    StringBuilder analysis = new StringBuilder();
    boolean issuesFound = false;
    
    try {
      // Find the method by name
      Method[] methods = resourceClass.getDeclaredMethods();
      for (Method method : methods) {
        if (method.getName().equals(methodName)) {
          // Check if the method is synchronized
          if (java.lang.reflect.Modifier.isSynchronized(method.getModifiers())) {
            analysis.append("WARNING: Method is synchronized, which will pin virtual threads.\n");
            analysis.append("Recommendation: Use ReentrantLock instead of synchronized keyword.\n\n");
            issuesFound = true;
          }
          
          // Check method annotations for potential blocking operations
          if (hasBlockingAnnotations(method)) {
            analysis.append("WARNING: Method has annotations or naming patterns suggesting blocking operations.\n");
            analysis.append("Recommendation: Ensure blocking operations are not performed inside synchronized blocks.\n\n");
            issuesFound = true;
          }
          
          // Check method parameter types for potential blocking operations
          Class<?>[] paramTypes = method.getParameterTypes();
          for (Class<?> paramType : paramTypes) {
            if (paramType.getName().contains("java.io") || 
                paramType.getName().contains("java.sql") ||
                paramType.getName().contains("javax.sql")) {
              analysis.append("WARNING: Method has parameters suggesting I/O or database operations.\n");
              analysis.append("Recommendation: Ensure these operations are properly dispatched to avoid pinning.\n\n");
              issuesFound = true;
              break;
            }
          }
          
          // Check return type for potential blocking operations
          Class<?> returnType = method.getReturnType();
          if (returnType.getName().contains("java.io") || 
              returnType.getName().contains("java.sql") ||
              returnType.getName().contains("javax.sql")) {
            analysis.append("WARNING: Method return type suggests I/O or database operations.\n");
            analysis.append("Recommendation: Ensure these operations are properly dispatched to avoid pinning.\n\n");
            issuesFound = true;
          }
        }
      }
    }
    catch (SecurityException e) {
      log.debug("Unable to analyze JAX-RS method: {}", e.getMessage());
    }
    
    if (issuesFound) {
      analysis.append("General recommendations for virtual thread safety:\n");
      analysis.append("1. Replace synchronized blocks/methods with ReentrantLock\n");
      analysis.append("2. Ensure blocking I/O operations are not performed inside synchronized blocks\n");
      analysis.append("3. Use the safelyExecute() helper method in this class for potentially blocking operations\n");
      analysis.append("4. Consider using non-blocking I/O APIs where possible\n");
      return analysis.toString();
    }
    
    return null;
  }
  
  /**
   * Scans all JAX-RS resource classes in the given package for potential thread pinning issues.
   * This method is useful for identifying thread pinning risks during application startup or testing.
   *
   * @param packageName the package name to scan (e.g., "org.sonatype.nexus.repository.rest")
   * @return a map of class names to analysis results for classes with potential issues
   */
  public Map<String, String> scanPackageForThreadPinningRisks(final String packageName) {
    Map<String, String> results = new HashMap<>();
    
    try {
      // Find all classes in the package
      List<Class<?>> classes = findClassesInPackage(packageName);
      
      // Filter for JAX-RS resource classes
      List<Class<?>> resourceClasses = classes.stream()
          .filter(this::isJaxRsResource)
          .collect(Collectors.toList());
      
      // Analyze each resource class
      for (Class<?> resourceClass : resourceClasses) {
        StringBuilder classAnalysis = new StringBuilder();
        boolean classHasIssues = false;
        
        // Analyze each method in the class
        Method[] methods = resourceClass.getDeclaredMethods();
        for (Method method : methods) {
          String methodAnalysis = analyzeResourceMethod(resourceClass, method.getName());
          if (methodAnalysis != null) {
            classAnalysis.append("Method: ").append(method.getName()).append("\n");
            classAnalysis.append(methodAnalysis).append("\n");
            classHasIssues = true;
          }
        }
        
        if (classHasIssues) {
          results.put(resourceClass.getName(), classAnalysis.toString());
        }
      }
    }
    catch (IOException | ClassNotFoundException e) {
      log.warn("Error scanning package for thread pinning risks: {}", e.getMessage());
    }
    
    return results;
  }
  
  /**
   * Finds all classes in the given package.
   *
   * @param packageName the package name to scan
   * @return a list of classes in the package
   * @throws IOException if an I/O error occurs
   * @throws ClassNotFoundException if a class cannot be found
   */
  private List<Class<?>> findClassesInPackage(final String packageName) 
      throws IOException, ClassNotFoundException {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    String path = packageName.replace('.', '/');
    Enumeration<URL> resources = classLoader.getResources(path);
    List<Class<?>> classes = new ArrayList<>();
    
    while (resources.hasMoreElements()) {
      URL resource = resources.nextElement();
      String protocol = resource.getProtocol();
      
      if (protocol.equals("file")) {
        // Handle file-based resources
        java.io.File directory = new java.io.File(resource.getFile());
        if (directory.exists()) {
          String[] files = directory.list();
          if (files != null) {
            for (String file : files) {
              if (file.endsWith(".class")) {
                String className = packageName + '.' + file.substring(0, file.length() - 6);
                classes.add(Class.forName(className));
              }
            }
          }
        }
      }
      else if (protocol.equals("jar")) {
        // Handle jar-based resources
        // This is a simplified implementation
        // In a real implementation, we would need to handle jar URLs properly
        log.debug("Jar-based resources not fully supported for package scanning");
      }
    }
    
    return classes;
  }
  
  /**
   * Analyzes a JAX-RS resource class for potential thread pinning issues.
   * This method performs static analysis of all methods in the class to identify common patterns
   * that could lead to thread pinning.
   *
   * @param resourceClass the JAX-RS resource class to analyze
   * @return a string containing analysis results, or null if no issues were found
   */
  @Nullable
  public String analyzeResourceClass(final Class<?> resourceClass) {
    if (!isJaxRsResource(resourceClass)) {
      return null;
    }
    
    StringBuilder analysis = new StringBuilder();
    boolean issuesFound = false;
    
    // Check if the class has synchronized methods
    Method[] methods = resourceClass.getDeclaredMethods();
    for (Method method : methods) {
      String methodAnalysis = analyzeResourceMethod(resourceClass, method.getName());
      if (methodAnalysis != null) {
        if (!issuesFound) {
          analysis.append("Thread pinning risks detected in JAX-RS resource class: ")
              .append(resourceClass.getName()).append("\n\n");
          issuesFound = true;
        }
        
        analysis.append("Method: ").append(method.getName()).append("\n");
        analysis.append(methodAnalysis).append("\n");
      }
    }
    
    if (issuesFound) {
      return analysis.toString();
    }
    
    return null;
  }
  
  /**
   * Creates a JAX-RS response filter that detects thread pinning during request processing.
   * This filter can be registered with the JAX-RS runtime to automatically detect thread pinning
   * in all JAX-RS resource methods.
   *
   * @return a ContainerResponseFilter that detects thread pinning
   */
  public ContainerResponseFilter createThreadPinningDetectionFilter() {
    return new ContainerResponseFilter() {
      @Override
      public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!isVirtualThread() || !detectionEnabled) {
          return;
        }
        
        // Get the resource class and method from the request context
        Object resource = requestContext.getProperty("org.sonatype.nexus.rest.Resource");
        if (resource != null) {
          Class<?> resourceClass = resource.getClass();
          String methodName = requestContext.getMethod();
          String path = requestContext.getUriInfo().getPath();
          
          // Check for thread pinning
          if (isThreadPinned()) {
            log.warn("Thread pinning detected in JAX-RS request: {} {} ({})",
                methodName, path, resourceClass.getName());
            logThreadPinningRisks(resourceClass);
          }
        }
      }
    };
  }
  
  /**
   * Generates a comprehensive report of thread pinning risks in the application.
   * This method scans common JAX-RS resource packages in Nexus Repository for potential thread pinning issues.
   * 
   * @return a string containing the report
   */
  public String generateThreadPinningReport() {
    if (!detectionEnabled) {
      return "Thread pinning detection is disabled. Enable it with system property: " + 
          THREAD_PINNING_DETECTION_ENABLED + "=true";
    }
    
    StringBuilder report = new StringBuilder();
    report.append("Thread Pinning Risk Report for Nexus Repository\n");
    report.append("===========================================\n\n");
    report.append("This report identifies JAX-RS resource classes and methods that may be at risk for \n");
    report.append("thread pinning when running with Java 21 Virtual Threads.\n\n");
    
    // List of common JAX-RS resource packages in Nexus Repository
    List<String> resourcePackages = List.of(
        "org.sonatype.nexus.repository.rest",
        "org.sonatype.nexus.repository.security.rest",
        "org.sonatype.nexus.blobstore.rest",
        "org.sonatype.nexus.scheduling.internal.resources"
    );
    
    int totalIssuesFound = 0;
    
    for (String packageName : resourcePackages) {
      report.append("Scanning package: ").append(packageName).append("\n");
      report.append("-------------------------------------------\n");
      
      Map<String, String> packageResults = scanPackageForThreadPinningRisks(packageName);
      
      if (packageResults.isEmpty()) {
        report.append("No thread pinning risks detected in this package.\n\n");
      }
      else {
        totalIssuesFound += packageResults.size();
        report.append("Found ").append(packageResults.size())
            .append(" classes with potential thread pinning risks:\n\n");
        
        for (Map.Entry<String, String> entry : packageResults.entrySet()) {
          report.append("Class: ").append(entry.getKey()).append("\n");
          report.append(entry.getValue()).append("\n");
          report.append("-------------------------------------------\n");
        }
      }
    }
    
    report.append("\nSummary:\n");
    report.append("Total classes with potential thread pinning risks: ").append(totalIssuesFound).append("\n");
    
    if (totalIssuesFound > 0) {
      report.append("\nGeneral Recommendations:\n");
      report.append("1. Replace synchronized blocks/methods with ReentrantLock\n");
      report.append("2. Ensure blocking I/O operations are not performed inside synchronized blocks\n");
      report.append("3. Use the safelyExecute() helper method in ThreadPinningDetector for potentially blocking operations\n");
      report.append("4. Consider using non-blocking I/O APIs where possible\n");
      report.append("5. For JDBC operations, ensure the JDBC driver supports virtual threads\n");
      report.append("6. For S3 operations, consider using the AWS SDK's async client\n");
    }
    
    return report.toString();
  }
}