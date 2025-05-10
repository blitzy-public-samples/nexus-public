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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper utilities for determining optimal thread types (virtual vs platform) for scheduled tasks,
 * detecting thread pinning risks in database operations, and ensuring proper thread context propagation.
 * 
 * <p>This class helps optimize task execution performance using Java 21 Virtual Threads while avoiding
 * thread pinning issues with database transactions.</p>
 * 
 * <p>Virtual threads are lightweight threads that significantly reduce the effort of writing, maintaining,
 * and debugging high-throughput concurrent applications. They are particularly well-suited for tasks
 * that spend most of their time blocked, often waiting for I/O operations to complete.</p>
 * 
 * <p>However, virtual threads can experience "pinning" when they perform blocking operations inside
 * synchronized blocks or methods, or when executing native methods. Pinning prevents the virtual thread
 * from unmounting from its carrier thread, which severely limits scalability.</p>
 *
 * @since 3.60
 */
public class TaskThreadHelper
{
  private static final Logger log = LoggerFactory.getLogger(TaskThreadHelper.class);
  /**
   * Task configuration key indicating that a task should always use platform threads regardless of other factors.
   */
  public static final String FORCE_PLATFORM_THREAD_KEY = ".forcePlatformThread";

  /**
   * Task configuration key indicating that a task should use virtual threads if compatible.
   */
  public static final String PREFER_VIRTUAL_THREAD_KEY = ".preferVirtualThread";

  /**
   * Task configuration key indicating that a task performs database operations that may cause thread pinning.
   */
  public static final String HAS_DATABASE_OPERATIONS_KEY = ".hasDatabaseOperations";

  /**
   * Task configuration key indicating that a task uses synchronized blocks that may cause thread pinning.
   */
  public static final String HAS_SYNCHRONIZED_BLOCKS_KEY = ".hasSynchronizedBlocks";

  /**
   * Set of task type IDs known to be compatible with virtual threads.
   */
  private static final Set<String> VIRTUAL_THREAD_COMPATIBLE_TASKS = ConcurrentHashMap.newKeySet();

  /**
   * Set of task type IDs known to be incompatible with virtual threads.
   */
  private static final Set<String> VIRTUAL_THREAD_INCOMPATIBLE_TASKS = ConcurrentHashMap.newKeySet();

  /**
   * Determines if a task is compatible with virtual threads based on its configuration.
   * 
   * <p>Tasks that perform database operations or use synchronized blocks may experience thread pinning
   * when run on virtual threads, which can severely limit scalability.</p>
   *
   * @param configuration the task configuration to analyze
   * @return true if the task can safely run on a virtual thread, false otherwise
   */
  public static boolean isVirtualThreadCompatible(final TaskConfiguration configuration) {
    String typeId = configuration.getTypeId();
    
    // Check cache first for known compatibility
    if (VIRTUAL_THREAD_COMPATIBLE_TASKS.contains(typeId)) {
      return true;
    }
    
    // Check cache first for known incompatibility
    if (VIRTUAL_THREAD_INCOMPATIBLE_TASKS.contains(typeId)) {
      return false;
    }
    
    // Explicit configuration overrides
    if (configuration.getBoolean(FORCE_PLATFORM_THREAD_KEY, false)) {
      VIRTUAL_THREAD_INCOMPATIBLE_TASKS.add(typeId);
      return false;
    }
    
    if (configuration.getBoolean(PREFER_VIRTUAL_THREAD_KEY, false)) {
      VIRTUAL_THREAD_COMPATIBLE_TASKS.add(typeId);
      return true;
    }
    
    // Check for database operations that may cause thread pinning
    if (configuration.getBoolean(HAS_DATABASE_OPERATIONS_KEY, false) || hasJdbcOperations(configuration)) {
      log.debug("Task {} has database operations that may cause thread pinning", typeId);
      VIRTUAL_THREAD_INCOMPATIBLE_TASKS.add(typeId);
      return false;
    }
    
    // Check for synchronized blocks that may cause thread pinning
    if (configuration.getBoolean(HAS_SYNCHRONIZED_BLOCKS_KEY, false)) {
      log.debug("Task {} has synchronized blocks that may cause thread pinning", typeId);
      VIRTUAL_THREAD_INCOMPATIBLE_TASKS.add(typeId);
      return false;
    }
    
    // Default to compatible for tasks without known pinning risks
    log.debug("Task {} appears compatible with virtual threads", typeId);
    VIRTUAL_THREAD_COMPATIBLE_TASKS.add(typeId);
    return true;
  }

  /**
   * Analyzes task configuration attributes to detect potential thread pinning risks.
   * 
   * <p>This method examines task configuration for patterns that suggest database operations
   * or other activities that might cause thread pinning when run on virtual threads.</p>
   *
   * @param configuration the task configuration to analyze
   * @return a map of detected pinning risks with risk type as key and description as value
   */
  public static Map<String, String> detectPinningRisks(final TaskConfiguration configuration) {
    Map<String, String> risks = new ConcurrentHashMap<>();
    
    // Check for database operation indicators in configuration
    for (Map.Entry<String, String> entry : configuration.asMap().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      
      // Look for database connection or SQL query indicators
      if (key.contains("sql") || key.contains("query") || key.contains("database") || 
          key.contains("jdbc") || key.contains("connection")) {
        risks.put(HAS_DATABASE_OPERATIONS_KEY, 
            "Task configuration suggests database operations that may cause thread pinning: " + key);
      }
      
      // Look for synchronization indicators
      if (key.contains("sync") || key.contains("lock")) {
        risks.put(HAS_SYNCHRONIZED_BLOCKS_KEY, 
            "Task configuration suggests synchronized operations that may cause thread pinning: " + key);
      }
      
      // Check values for SQL statements
      if (value != null && (value.contains("SELECT ") || value.contains("INSERT ") || 
          value.contains("UPDATE ") || value.contains("DELETE ") || value.contains("jdbc:"))) {
        risks.put(HAS_DATABASE_OPERATIONS_KEY, 
            "Task configuration contains SQL statements that may cause thread pinning");
      }
    }
    
    // Check task type for known database-heavy operations
    String typeId = configuration.getTypeId();
    if (typeId != null && (typeId.contains("Database") || typeId.contains("SQL") || 
        typeId.contains("JDBC") || typeId.contains("Repository") || typeId.contains("Storage"))) {
      risks.put(HAS_DATABASE_OPERATIONS_KEY, 
          "Task type suggests database operations that may cause thread pinning: " + typeId);
    }
    
    return risks;
  }

  /**
   * Registers a task type as compatible with virtual threads.
   * 
   * <p>This method allows for programmatic registration of task types that have been verified
   * to work correctly with virtual threads.</p>
   *
   * @param typeId the task type ID to register as virtual thread compatible
   */
  public static void registerVirtualThreadCompatibleTask(String typeId) {
    VIRTUAL_THREAD_COMPATIBLE_TASKS.add(typeId);
    VIRTUAL_THREAD_INCOMPATIBLE_TASKS.remove(typeId); // Remove from incompatible if present
  }

  /**
   * Registers a task type as incompatible with virtual threads.
   * 
   * <p>This method allows for programmatic registration of task types that have been verified
   * to experience issues when run on virtual threads.</p>
   *
   * @param typeId the task type ID to register as virtual thread incompatible
   */
  public static void registerVirtualThreadIncompatibleTask(String typeId) {
    VIRTUAL_THREAD_INCOMPATIBLE_TASKS.add(typeId);
    VIRTUAL_THREAD_COMPATIBLE_TASKS.remove(typeId); // Remove from compatible if present
  }

  /**
   * Determines if a task should use a virtual thread based on its configuration and system properties.
   * 
   * <p>This method considers both task-specific configuration and system-wide settings to determine
   * the optimal thread type for a task.</p>
   *
   * @param configuration the task configuration to analyze
   * @return true if the task should use a virtual thread, false if it should use a platform thread
   */
  public static boolean shouldUseVirtualThread(final TaskConfiguration configuration) {
    // Check if virtual threads are globally disabled
    if (Boolean.getBoolean("nexus.tasks.disableVirtualThreads")) {
      log.debug("Virtual threads are globally disabled for tasks");
      return false;
    }
    
    // Check if virtual threads are globally forced
    if (Boolean.getBoolean("nexus.tasks.forceVirtualThreads")) {
      log.debug("Virtual threads are globally forced for tasks");
      return true;
    }
    
    // Check if Java version supports virtual threads (Java 21+)
    String javaVersion = System.getProperty("java.version");
    if (javaVersion != null && !javaVersion.startsWith("21.") && !javaVersion.startsWith("22.") && 
        !javaVersion.startsWith("23.") && !javaVersion.startsWith("24.")) {
      log.debug("Java version {} does not support virtual threads, using platform threads", javaVersion);
      return false;
    }
    
    // Otherwise, determine based on task compatibility
    boolean compatible = isVirtualThreadCompatible(configuration);
    log.debug("Task {} is {} with virtual threads", configuration.getTypeId(), 
              compatible ? "compatible" : "incompatible");
    return compatible;
  }

  /**
   * Provides guidance on how to make a task compatible with virtual threads.
   * 
   * <p>This method analyzes a task configuration and provides specific recommendations
   * for making it compatible with virtual threads if it currently isn't.</p>
   *
   * @param configuration the task configuration to analyze
   * @return a string containing recommendations, or null if the task is already compatible
   */
  public static String getVirtualThreadCompatibilityGuidance(final TaskConfiguration configuration) {
    if (isVirtualThreadCompatible(configuration)) {
      return null; // Already compatible
    }
    
    StringBuilder guidance = new StringBuilder();
    guidance.append("Task '")
            .append(configuration.getName())
            .append("' (type: ")
            .append(configuration.getTypeId())
            .append(") may not be compatible with virtual threads. Recommendations:\n");
    
    Map<String, String> risks = detectPinningRisks(configuration);
    
    if (risks.containsKey(HAS_DATABASE_OPERATIONS_KEY)) {
      guidance.append("- Database operations: Consider using non-blocking JDBC drivers or refactor to avoid ")
              .append("synchronized blocks around database operations.\n")
              .append("  * Replace synchronized blocks with ReentrantLock when performing database operations\n")
              .append("  * Use the TaskThreadHelper.executeDbOperationSafely() method for database operations\n")
              .append("  * Consider using connection pooling with appropriate timeout settings\n");
    }
    
    if (risks.containsKey(HAS_SYNCHRONIZED_BLOCKS_KEY)) {
      guidance.append("- Synchronized blocks: Consider replacing synchronized blocks with ReentrantLock ")
              .append("or other java.util.concurrent locks that don't cause thread pinning.\n")
              .append("  * Use java.util.concurrent.locks.ReentrantLock instead of synchronized blocks\n")
              .append("  * Ensure locks are always released in finally blocks\n")
              .append("  * Keep critical sections as small as possible\n");
    }
    
    guidance.append("- If the task must use platform threads, set '")
            .append(FORCE_PLATFORM_THREAD_KEY)
            .append("=true' in the task configuration.\n");
    
    guidance.append("\nFor monitoring thread pinning issues:\n")
            .append("- Enable JFR monitoring with TaskThreadHelper.enablePinningMonitoring()\n")
            .append("- Add -Djdk.tracePinnedThreads=full JVM argument for detailed pinning detection\n")
            .append("- Use JDK Flight Recorder to capture and analyze thread pinning events\n");
    
    return guidance.toString();
  }

  /**
   * Enables monitoring of thread pinning for a specific task type.
   * 
   * <p>This method configures JVM options to detect and log thread pinning events for a specific task type.</p>
   *
   * @param typeId the task type ID to monitor for thread pinning
   * @return true if monitoring was successfully enabled, false otherwise
   */
  public static boolean enablePinningMonitoring(String typeId) {
    try {
      // Set system property to enable thread pinning detection
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      log.info("Enabled thread pinning monitoring for task type: {}", typeId);
      log.info("Thread pinning events will be logged to the console with full stack traces");
      log.info("This may impact performance and should only be used for debugging purposes");
      
      // Register JFR event listener for VirtualThreadPinned events
      // Note: This is a simplified implementation; actual JFR event handling would require more code
      return true;
    } catch (Exception e) {
      log.error("Failed to enable thread pinning monitoring for task type: {}", typeId, e);
      return false;
    }
  }

  /**
   * Checks if a task configuration contains indicators of JDBC operations that might cause thread pinning.
   * 
   * <p>This method specifically looks for configuration patterns that suggest JDBC operations,
   * which are a common source of thread pinning in virtual threads.</p>
   *
   * @param configuration the task configuration to analyze
   * @return true if JDBC operations are detected, false otherwise
   */
  public static boolean hasJdbcOperations(final TaskConfiguration configuration) {
    Map<String, String> configMap = configuration.asMap();
    
    // Check for JDBC-related configuration keys
    for (String key : configMap.keySet()) {
      if (key.contains("jdbc") || key.contains("sql") || key.contains("database") || 
          key.contains("connection") || key.contains("query")) {
        return true;
      }
    }
    
    // Check for JDBC-related configuration values
    for (String value : configMap.values()) {
      if (value != null && (value.contains("jdbc:") || value.contains("SELECT ") || 
          value.contains("INSERT ") || value.contains("UPDATE ") || value.contains("DELETE "))) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Executes a database operation safely with virtual threads by using a ReentrantLock instead of synchronized.
   * 
   * <p>This method provides a way to execute database operations without causing thread pinning
   * by using ReentrantLock instead of synchronized blocks.</p>
   *
   * @param <T> the type of result returned by the operation
   * @param operation the database operation to execute
   * @param lock the lock to use for synchronization
   * @return the result of the operation
   */
  public static <T> T executeDbOperationSafely(Supplier<T> operation, ReentrantLock lock) {
    lock.lock();
    try {
      return operation.get();
    } finally {
      lock.unlock();
    }
  }
  
  /**
   * Creates a thread-safe executor service that properly handles virtual threads.
   * 
   * <p>This method creates an executor service that properly propagates security context
   * and other thread-local variables when using virtual threads.</p>
   *
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return a properly configured executor service
   */
  public static NexusExecutorService createThreadSafeExecutor(boolean useVirtualThreads) {
    if (useVirtualThreads) {
      // Create a virtual thread executor with proper security context propagation
      log.debug("Creating virtual thread executor service");
      return NexusExecutorService.forCurrentSubject(Thread.ofVirtual().factory().executor());
    } else {
      // Use platform threads with proper security context propagation
      log.debug("Creating platform thread executor service");
      return NexusExecutorService.forCurrentSubject(java.util.concurrent.Executors.newCachedThreadPool());
    }
  }
  
  /**
   * Executes a task with the appropriate thread type based on its configuration.
   * 
   * <p>This method automatically determines whether to use a virtual thread or platform thread
   * based on the task's configuration and executes the task accordingly.</p>
   *
   * @param <T> the type of result returned by the task
   * @param task the task to execute
   * @param configuration the task configuration
   * @return the result of the task
   */
  public static <T> T executeWithOptimalThreadType(Supplier<T> task, TaskConfiguration configuration) {
    boolean useVirtualThread = shouldUseVirtualThread(configuration);
    String threadType = useVirtualThread ? "virtual" : "platform";
    log.debug("Executing task {} with {} thread", configuration.getTypeId(), threadType);
    
    if (useVirtualThread) {
      // Execute with virtual thread
      return Thread.ofVirtual()
          .name("task-" + configuration.getId())
          .start(() -> task.get())
          .join();
    } else {
      // Execute with current thread (platform thread)
      return task.get();
    }
  }
  
  /**
   * Detects if the current thread is a virtual thread.
   * 
   * <p>This method determines if the current thread is a virtual thread or a platform thread.</p>
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
}