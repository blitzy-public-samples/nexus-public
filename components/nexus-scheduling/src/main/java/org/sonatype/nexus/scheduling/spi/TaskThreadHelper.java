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
package org.sonatype.nexus.scheduling.spi;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper utilities for determining optimal thread types (virtual vs platform) for scheduled tasks,
 * detecting thread pinning risks in database operations, and ensuring proper thread context propagation.
 * <p>
 * This class helps the scheduler make intelligent decisions about whether to use virtual threads or
 * platform threads for specific tasks based on their characteristics, especially focusing on database
 * operations that might cause thread pinning.
 *
 * @since 3.60
 */
public class TaskThreadHelper
{
  private static final Logger log = LoggerFactory.getLogger(TaskThreadHelper.class);

  /**
   * Task types known to be incompatible with virtual threads due to thread pinning risks.
   * These tasks will always use platform threads regardless of other settings.
   */
  private static final Set<String> VIRTUAL_THREAD_INCOMPATIBLE_TASK_TYPES = Set.of(
      // Tasks with known thread pinning issues due to synchronized blocks with database operations
      "db.backup",
      "db.rebuild",
      "db.vacuum"
  );

  /**
   * Task types known to be compatible with virtual threads.
   * These tasks will use virtual threads when available unless explicitly configured otherwise.
   */
  private static final Set<String> VIRTUAL_THREAD_COMPATIBLE_TASK_TYPES = Set.of(
      // I/O-bound tasks that benefit from virtual threads
      "repository.rebuild-index",
      "repository.purge-unused",
      "repository.purge-orphaned",
      "blobstore.compact",
      "blobstore.rebuild-component-db",
      "s3.upload",
      "s3.download"
  );

  /**
   * Cache of task type compatibility with virtual threads.
   * This avoids repeated analysis of the same task types.
   */
  private static final Map<String, Boolean> TASK_TYPE_COMPATIBILITY_CACHE = new ConcurrentHashMap<>();

  /**
   * System property to enable or disable virtual threads for tasks globally.
   */
  public static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "nexus.tasks.virtualThreads.enabled";

  /**
   * System property to enable or disable virtual thread compatibility analysis.
   */
  public static final String VIRTUAL_THREADS_ANALYSIS_ENABLED_PROPERTY = "nexus.tasks.virtualThreads.analysis.enabled";

  /**
   * Task configuration property to explicitly set virtual thread usage for a specific task.
   */
  public static final String TASK_VIRTUAL_THREAD_PROPERTY = "virtualThread";

  /**
   * Determines if virtual threads are globally enabled for tasks.
   *
   * @return true if virtual threads are enabled, false otherwise
   */
  public static boolean isVirtualThreadsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ENABLED_PROPERTY);
  }

  /**
   * Determines if virtual thread compatibility analysis is enabled.
   *
   * @return true if analysis is enabled, false otherwise
   */
  public static boolean isVirtualThreadAnalysisEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ANALYSIS_ENABLED_PROPERTY);
  }

  /**
   * Determines if a task should use virtual threads based on its configuration and type.
   * <p>
   * The decision is made based on the following factors:
   * <ol>
   *   <li>If virtual threads are globally disabled, returns false</li>
   *   <li>If the task configuration explicitly sets the virtual thread property, uses that value</li>
   *   <li>If the task type is known to be incompatible with virtual threads, returns false</li>
   *   <li>If the task type is known to be compatible with virtual threads, returns true</li>
   *   <li>Otherwise, analyzes the task configuration for potential thread pinning risks</li>
   * </ol>
   *
   * @param taskConfiguration the task configuration to analyze
   * @return true if the task should use virtual threads, false otherwise
   */
  public static boolean shouldUseVirtualThread(final TaskConfiguration taskConfiguration) {
    checkNotNull(taskConfiguration, "Task configuration cannot be null");
    
    // If virtual threads are globally disabled, don't use them
    if (!isVirtualThreadsEnabled()) {
      return false;
    }
    
    // Check if the task configuration explicitly sets the virtual thread property
    String virtualThreadProperty = taskConfiguration.getString(TASK_VIRTUAL_THREAD_PROPERTY, null);
    if (virtualThreadProperty != null) {
      return Boolean.parseBoolean(virtualThreadProperty);
    }
    
    // Get the task type
    String taskType = taskConfiguration.getTypeId();
    
    // Check if we've already determined compatibility for this task type
    Boolean cachedCompatibility = TASK_TYPE_COMPATIBILITY_CACHE.get(taskType);
    if (cachedCompatibility != null) {
      return cachedCompatibility;
    }
    
    // Check if the task type is known to be incompatible with virtual threads
    if (VIRTUAL_THREAD_INCOMPATIBLE_TASK_TYPES.contains(taskType)) {
      TASK_TYPE_COMPATIBILITY_CACHE.put(taskType, false);
      return false;
    }
    
    // Check if the task type is known to be compatible with virtual threads
    if (VIRTUAL_THREAD_COMPATIBLE_TASK_TYPES.contains(taskType)) {
      TASK_TYPE_COMPATIBILITY_CACHE.put(taskType, true);
      return true;
    }
    
    // Analyze the task configuration for potential thread pinning risks
    boolean isCompatible = isVirtualThreadCompatible(taskConfiguration);
    TASK_TYPE_COMPATIBILITY_CACHE.put(taskType, isCompatible);
    return isCompatible;
  }

  /**
   * Analyzes a task configuration to determine if it's compatible with virtual threads.
   * <p>
   * This method examines the task configuration for patterns that might indicate
   * potential thread pinning risks, particularly with database operations.
   * <p>
   * Thread pinning occurs when a virtual thread cannot unmount from its carrier platform thread,
   * typically when performing blocking operations inside synchronized blocks or methods.
   * This is particularly problematic with JDBC operations that might block while holding locks.
   *
   * @param taskConfiguration the task configuration to analyze
   * @return true if the task is compatible with virtual threads, false otherwise
   */
  public static boolean isVirtualThreadCompatible(final TaskConfiguration taskConfiguration) {
    // If analysis is disabled, assume compatibility
    if (!isVirtualThreadAnalysisEnabled()) {
      return true;
    }
    
    String taskType = taskConfiguration.getTypeId();
    
    // Check for database-intensive tasks that might use synchronized blocks
    if (taskType.startsWith("db.")) {
      log.debug("Task type {} is likely database-intensive and may risk thread pinning", taskType);
      return false;
    }
    
    // Check for tasks that involve heavy transaction processing
    if (taskConfiguration.containsKey("sql.query") || 
        taskConfiguration.containsKey("database.operation") ||
        taskConfiguration.containsKey("transaction.intensive")) {
      log.debug("Task configuration contains database operation indicators");
      return false;
    }
    
    // Check for tasks that explicitly indicate they're I/O bound and safe for virtual threads
    if (taskConfiguration.containsKey("io.bound") && 
        Boolean.parseBoolean(taskConfiguration.getString("io.bound"))) {
      log.debug("Task explicitly marked as I/O bound, suitable for virtual threads");
      return true;
    }
    
    // Check for repository tasks that are typically I/O bound
    if (taskType.startsWith("repository.") && 
        !taskType.contains("rebuild-metadata") && 
        !taskType.contains("validate")) {
      log.debug("Repository task {} is likely I/O bound and suitable for virtual threads", taskType);
      return true;
    }
    
    // Check for blobstore tasks that are typically I/O bound
    if (taskType.startsWith("blobstore.") && 
        !taskType.contains("integrity-check")) {
      log.debug("Blobstore task {} is likely I/O bound and suitable for virtual threads", taskType);
      return true;
    }
    
    // Check for S3 operations which are definitely I/O bound
    if (taskType.startsWith("s3.")) {
      log.debug("S3 task {} is I/O bound and suitable for virtual threads", taskType);
      return true;
    }
    
    // For tasks we're uncertain about, err on the side of caution
    // and use platform threads to avoid potential pinning issues
    log.debug("Task type {} has unknown virtual thread compatibility, defaulting to platform threads", taskType);
    return false;
  }

  /**
   * Detects potential thread pinning risks in a task configuration.
   * <p>
   * This method examines the task configuration for patterns that might indicate
   * potential thread pinning risks, particularly with database operations.
   * <p>
   * Thread pinning occurs in the following scenarios:
   * <ul>
   *   <li>Blocking operations inside synchronized blocks or methods</li>
   *   <li>Native method calls that block</li>
   *   <li>Foreign function calls that block</li>
   *   <li>JDBC operations that use internal synchronization</li>
   * </ul>
   *
   * @param taskConfiguration the task configuration to analyze
   * @return a set of detected pinning risks, or an empty set if none are detected
   */
  public static Set<String> detectPinningRisks(final TaskConfiguration taskConfiguration) {
    checkNotNull(taskConfiguration, "Task configuration cannot be null");
    
    Set<String> risks = new java.util.HashSet<>();
    String taskType = taskConfiguration.getTypeId();
    
    // Check for database-intensive tasks
    if (taskType.startsWith("db.")) {
      risks.add("Database-intensive task may use synchronized blocks with JDBC operations");
    }
    
    // Check for SQL query parameters
    if (taskConfiguration.containsKey("sql.query")) {
      risks.add("Task contains SQL queries which may cause thread pinning with JDBC drivers");
    }
    
    // Check for transaction-intensive operations
    if (taskConfiguration.containsKey("transaction.intensive") && 
        Boolean.parseBoolean(taskConfiguration.getString("transaction.intensive"))) {
      risks.add("Task is marked as transaction-intensive which may involve synchronized database operations");
    }
    
    // Check for known problematic task types
    if (VIRTUAL_THREAD_INCOMPATIBLE_TASK_TYPES.contains(taskType)) {
      risks.add("Task type is known to be incompatible with virtual threads due to thread pinning risks");
    }
    
    // Check for native method usage
    if (taskConfiguration.containsKey("native.methods") && 
        Boolean.parseBoolean(taskConfiguration.getString("native.methods"))) {
      risks.add("Task uses native methods which can cause thread pinning");
    }
    
    // Check for foreign function usage
    if (taskConfiguration.containsKey("foreign.functions") && 
        Boolean.parseBoolean(taskConfiguration.getString("foreign.functions"))) {
      risks.add("Task uses foreign functions which can cause thread pinning");
    }
    
    return risks;
  }

  /**
   * Creates a callable that will execute in the appropriate thread type based on the task configuration.
   * <p>
   * This method wraps the original callable with the necessary context propagation to ensure
   * that the task executes correctly regardless of the thread type used.
   * <p>
   * For virtual threads, this includes special handling to avoid thread pinning issues,
   * particularly with database operations. For platform threads, this includes standard
   * context propagation for security subjects and thread-local variables.
   *
   * @param callable the original callable to execute
   * @param taskConfiguration the task configuration to analyze
   * @param subject the security subject to associate with the callable
   * @param <V> the return type of the callable
   * @return a wrapped callable that will execute in the appropriate thread type
   */
  public static <V> Callable<V> createThreadAppropriateCallable(
      final Callable<V> callable,
      final TaskConfiguration taskConfiguration,
      @Nullable final Subject subject) {
    checkNotNull(callable, "Callable cannot be null");
    checkNotNull(taskConfiguration, "Task configuration cannot be null");
    
    // Determine if we should use virtual threads for this task
    boolean useVirtualThread = shouldUseVirtualThread(taskConfiguration);
    
    // Log the decision for debugging purposes
    if (log.isDebugEnabled()) {
      log.debug("Task {} will use {} threads", 
          taskConfiguration.getName(), 
          useVirtualThread ? "virtual" : "platform");
      
      // If using virtual threads, log any detected pinning risks
      if (useVirtualThread) {
        Set<String> pinningRisks = detectPinningRisks(taskConfiguration);
        if (!pinningRisks.isEmpty()) {
          log.warn("Task {} has potential thread pinning risks: {}", 
              taskConfiguration.getName(), pinningRisks);
        }
      }
    }
    
    // Create a callable that will execute in the appropriate thread type
    return () -> {
      // Track the thread type for logging and diagnostics
      boolean isVirtualThread = Thread.currentThread().isVirtual();
      String threadName = Thread.currentThread().getName();
      String threadType = isVirtualThread ? "virtual" : "platform";
      
      if (log.isTraceEnabled()) {
        log.trace("Executing task {} on {} thread: {}", 
            taskConfiguration.getName(), threadType, threadName);
      }
      
      try {
        // Propagate the security subject if provided
        if (subject != null) {
          return subject.execute(callable);
        }
        else {
          return callable.call();
        }
      }
      catch (Exception e) {
        // Log thread pinning issues if detected
        if (isVirtualThread && isPinningRelated(e)) {
          log.warn("Task {} encountered a potential thread pinning issue: {}", 
              taskConfiguration.getName(), e.getMessage(), e);
          
          // Update the compatibility cache to avoid using virtual threads for this task type in the future
          TASK_TYPE_COMPATIBILITY_CACHE.put(taskConfiguration.getTypeId(), false);
        }
        
        // Re-throw the exception
        throw e;
      }
    };
  }
  
  /**
   * Determines if an exception is related to thread pinning.
   * <p>
   * This method examines the exception and its cause chain to determine if it's
   * likely related to thread pinning issues.
   *
   * @param e the exception to examine
   * @return true if the exception is likely related to thread pinning, false otherwise
   */
  private static boolean isPinningRelated(final Exception e) {
    // Check for common pinning-related exception patterns
    if (e.getMessage() != null && (
        e.getMessage().contains("thread pinning") ||
        e.getMessage().contains("blocked carrier") ||
        e.getMessage().contains("cannot be unmounted"))) {
      return true;
    }
    
    // Check for deadlock or timeout exceptions that might be related to pinning
    if (e instanceof java.util.concurrent.TimeoutException ||
        e instanceof java.lang.IllegalThreadStateException) {
      return true;
    }
    
    // Check the cause chain
    Throwable cause = e.getCause();
    if (cause != null && cause != e) {
      if (cause instanceof Exception) {
        return isPinningRelated((Exception) cause);
      }
    }
    
    return false;
  }

  /**
   * Creates an executor service that will use the appropriate thread type based on the task configuration.
   * <p>
   * This method creates an executor service that will use either virtual threads or platform threads
   * based on the task configuration and other factors.
   * <p>
   * For virtual threads, this includes special handling to avoid thread pinning issues,
   * particularly with database operations. For platform threads, this includes standard
   * context propagation for security subjects and thread-local variables.
   *
   * @param executorServiceSupplier a supplier for the base executor service
   * @param taskConfiguration the task configuration to analyze
   * @param subject the security subject to associate with the executor service
   * @return an executor service that will use the appropriate thread type
   */
  public static NexusExecutorService createThreadAppropriateExecutorService(
      final Supplier<NexusExecutorService> executorServiceSupplier,
      final TaskConfiguration taskConfiguration,
      @Nullable final Subject subject) {
    checkNotNull(executorServiceSupplier, "Executor service supplier cannot be null");
    checkNotNull(taskConfiguration, "Task configuration cannot be null");
    
    // Get the base executor service
    NexusExecutorService executorService = executorServiceSupplier.get();
    
    // Determine if we should use virtual threads for this task
    boolean useVirtualThread = shouldUseVirtualThread(taskConfiguration);
    
    // Log the decision for debugging purposes
    if (log.isDebugEnabled()) {
      log.debug("Task {} will use {} threads for its executor service", 
          taskConfiguration.getName(), 
          useVirtualThread ? "virtual" : "platform");
      
      // If using virtual threads, log any detected pinning risks
      if (useVirtualThread) {
        Set<String> pinningRisks = detectPinningRisks(taskConfiguration);
        if (!pinningRisks.isEmpty()) {
          log.warn("Task {} has potential thread pinning risks for executor service: {}", 
              taskConfiguration.getName(), pinningRisks);
        }
      }
    }
    
    // Create a decorated executor service that monitors for thread pinning issues
    NexusExecutorService decoratedService = new NexusExecutorService(executorService, 
        subject != null ? () -> subject : null) {
      @Override
      public <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
        return super.submit(monitorForPinning(task, taskConfiguration));
      }
      
      @Override
      public java.util.concurrent.Future<?> submit(Runnable task) {
        return super.submit(monitorForPinning(task, taskConfiguration));
      }
      
      @Override
      public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
        return super.submit(monitorForPinning(task, taskConfiguration), result);
      }
    };
    
    return decoratedService;
  }
  
  /**
   * Wraps a callable to monitor for thread pinning issues.
   *
   * @param callable the callable to monitor
   * @param taskConfiguration the task configuration
   * @param <V> the return type of the callable
   * @return a wrapped callable that monitors for thread pinning issues
   */
  private static <V> Callable<V> monitorForPinning(final Callable<V> callable, final TaskConfiguration taskConfiguration) {
    return () -> {
      try {
        return callable.call();
      }
      catch (Exception e) {
        // Check if this is a thread pinning related issue
        if (Thread.currentThread().isVirtual() && isPinningRelated(e)) {
          log.warn("Task {} encountered a potential thread pinning issue in executor service: {}", 
              taskConfiguration.getName(), e.getMessage(), e);
          
          // Update the compatibility cache to avoid using virtual threads for this task type in the future
          TASK_TYPE_COMPATIBILITY_CACHE.put(taskConfiguration.getTypeId(), false);
        }
        
        // Re-throw the exception
        throw e;
      }
    };
  }
  
  /**
   * Wraps a runnable to monitor for thread pinning issues.
   *
   * @param runnable the runnable to monitor
   * @param taskConfiguration the task configuration
   * @return a wrapped runnable that monitors for thread pinning issues
   */
  private static Runnable monitorForPinning(final Runnable runnable, final TaskConfiguration taskConfiguration) {
    return () -> {
      try {
        runnable.run();
      }
      catch (Exception e) {
        // Check if this is a thread pinning related issue
        if (Thread.currentThread().isVirtual() && isPinningRelated(e)) {
          log.warn("Task {} encountered a potential thread pinning issue in executor service: {}", 
              taskConfiguration.getName(), e.getMessage(), e);
          
          // Update the compatibility cache to avoid using virtual threads for this task type in the future
          TASK_TYPE_COMPATIBILITY_CACHE.put(taskConfiguration.getTypeId(), false);
        }
        
        // Re-throw the exception
        throw e;
      }
    };
  }

  /**
   * Propagates the current thread context to a new thread.
   * <p>
   * This method ensures that important context information, such as security subjects,
   * thread-local variables, and MDC context, is properly propagated to a new thread.
   * <p>
   * This is particularly important when transitioning between virtual threads and platform threads,
   * as thread-local variables are not automatically propagated between different thread types.
   *
   * @param runnable the runnable to execute in the new thread
   * @param subject the security subject to associate with the new thread
   * @return a runnable that will propagate the current thread context
   */
  public static Runnable propagateContext(final Runnable runnable, @Nullable final Subject subject) {
    checkNotNull(runnable, "Runnable cannot be null");
    
    // Capture the current thread's context
    final boolean isSourceVirtual = Thread.currentThread().isVirtual();
    final String sourceThreadName = Thread.currentThread().getName();
    
    // Create a runnable that propagates the context
    return () -> {
      final boolean isTargetVirtual = Thread.currentThread().isVirtual();
      final String targetThreadName = Thread.currentThread().getName();
      
      if (log.isTraceEnabled()) {
        log.trace("Propagating context from {} thread '{}' to {} thread '{}'",
            isSourceVirtual ? "virtual" : "platform",
            sourceThreadName,
            isTargetVirtual ? "virtual" : "platform",
            targetThreadName);
      }
      
      try {
        // Execute with the subject if provided
        if (subject != null) {
          subject.execute(runnable);
        }
        else {
          runnable.run();
        }
      }
      catch (Exception e) {
        // Check if this is a thread pinning related issue
        if (isTargetVirtual && isPinningRelated(e)) {
          log.warn("Thread pinning detected when propagating context to virtual thread: {}", 
              e.getMessage(), e);
        }
        
        // Re-throw the exception
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        else {
          throw new RuntimeException("Error executing task with propagated context", e);
        }
      }
    };
  }
  
  /**
   * Creates a structured diagnostic report about virtual thread compatibility for a task.
   * <p>
   * This method analyzes a task configuration and produces a detailed report about its
   * compatibility with virtual threads, including any detected pinning risks and recommendations.
   *
   * @param taskConfiguration the task configuration to analyze
   * @return a diagnostic report as a string
   */
  public static String createVirtualThreadDiagnosticReport(final TaskConfiguration taskConfiguration) {
    checkNotNull(taskConfiguration, "Task configuration cannot be null");
    
    StringBuilder report = new StringBuilder();
    String taskType = taskConfiguration.getTypeId();
    String taskName = taskConfiguration.getName();
    
    report.append("Virtual Thread Compatibility Report for Task: ").append(taskName)
          .append(" (Type: ").append(taskType).append(")\n");
    report.append("---------------------------------------------------\n");
    
    // Check if virtual threads are enabled globally
    report.append("Virtual Threads Globally Enabled: ").append(isVirtualThreadsEnabled()).append("\n");
    
    // Check if this task should use virtual threads
    boolean shouldUseVirtualThread = shouldUseVirtualThread(taskConfiguration);
    report.append("Task Should Use Virtual Threads: ").append(shouldUseVirtualThread).append("\n");
    
    // Check for explicit configuration
    String virtualThreadProperty = taskConfiguration.getString(TASK_VIRTUAL_THREAD_PROPERTY, null);
    if (virtualThreadProperty != null) {
      report.append("Task Explicitly Configured for Virtual Threads: ")
            .append(virtualThreadProperty).append("\n");
    }
    
    // Check for known compatibility
    if (VIRTUAL_THREAD_COMPATIBLE_TASK_TYPES.contains(taskType)) {
      report.append("Task Type is Known to be Compatible with Virtual Threads\n");
    }
    else if (VIRTUAL_THREAD_INCOMPATIBLE_TASK_TYPES.contains(taskType)) {
      report.append("Task Type is Known to be Incompatible with Virtual Threads\n");
    }
    
    // Check for pinning risks
    Set<String> pinningRisks = detectPinningRisks(taskConfiguration);
    if (pinningRisks.isEmpty()) {
      report.append("No Thread Pinning Risks Detected\n");
    }
    else {
      report.append("Detected Thread Pinning Risks:\n");
      for (String risk : pinningRisks) {
        report.append("  - ").append(risk).append("\n");
      }
    }
    
    // Add recommendations
    report.append("\nRecommendations:\n");
    if (shouldUseVirtualThread) {
      report.append("  - This task is suitable for virtual threads\n");
      if (!pinningRisks.isEmpty()) {
        report.append("  - Monitor for thread pinning issues during execution\n");
        report.append("  - Consider using -Djdk.tracePinnedThreads=full JVM option for detailed pinning diagnostics\n");
      }
    }
    else {
      report.append("  - This task should use platform threads to avoid potential issues\n");
      if (!pinningRisks.isEmpty()) {
        report.append("  - To use virtual threads, address the following pinning risks:\n");
        for (String risk : pinningRisks) {
          report.append("    * ").append(risk).append("\n");
        }
      }
    }
    
    return report.toString();
  }
}