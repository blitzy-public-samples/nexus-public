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
package org.sonatype.nexus.scheduling;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.events.TaskScheduledEvent;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;
import org.sonatype.nexus.thread.NexusExecutorService;
import org.sonatype.nexus.thread.NexusThreadFactory;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.app.FeatureFlags.CHANGE_REPO_BLOBSTORE_TASK_ENABLED_NAMED;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Default {@link TaskScheduler} implementation with Java 21 Virtual Thread support.
 * <p>
 * This implementation has been enhanced to support Java 21 Virtual Threads, providing:
 * <ul>
 *   <li>Proper security context propagation for Apache Shiro when using Virtual Threads</li>
 *   <li>Support for determining when to use Virtual Threads versus platform threads</li>
 *   <li>TaskInfo context propagation across thread boundaries</li>
 *   <li>Schedule-related logic compatible with Virtual Thread scheduling characteristics</li>
 *   <li>Logging and diagnostics for thread-pinning issues</li>
 * </ul>
 *
 * @since 3.0
 */
@Singleton
@Named
public class TaskSchedulerImpl
    extends ComponentSupport
    implements TaskScheduler
{
  /**
   * Logger for virtual thread diagnostics, separate from the component logger
   * to allow for more granular control of logging levels.
   */
  private static final Logger VIRTUAL_THREAD_LOGGER = LoggerFactory.getLogger(
      TaskSchedulerImpl.class.getName() + ".VirtualThreads");
      
  /**
   * Thread MX Bean for monitoring thread-related metrics.
   */
  private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
  
  /**
   * Lock for thread-safe operations that should not use synchronized blocks
   * to avoid virtual thread pinning.
   */
  private final ReentrantLock lock = new ReentrantLock();

  protected static final String REPO_MOVE_TYPE_ID = "repository.move";

  private final EventManager eventManager;

  private final TaskFactory taskFactory;

  private final Provider<SchedulerSPI> scheduler;
  
  /**
   * Flag to determine if virtual threads should be used for task execution.
   * Virtual threads are more efficient for I/O-bound tasks but may not be
   * suitable for all workloads.
   */
  @Inject
  @Named("${nexus.scheduler.useVirtualThreads:-true}")
  protected boolean useVirtualThreads;
  
  /**
   * Flag to enable thread pinning diagnostics for virtual threads.
   * When enabled, logs warnings when virtual threads get pinned to carrier threads.
   */
  @Inject
  @Named("${nexus.scheduler.threadPinningDiagnostics:-false}")
  protected boolean threadPinningDiagnostics;

  @Inject
  @Named(CHANGE_REPO_BLOBSTORE_TASK_ENABLED_NAMED)
  protected boolean changeRepoBlobstoreTaskEnabled;

  @Inject
  public TaskSchedulerImpl(final EventManager eventManager,
                           final TaskFactory taskFactory,
                           final Provider<SchedulerSPI> scheduler)
  {
    this.eventManager = checkNotNull(eventManager);
    this.taskFactory = checkNotNull(taskFactory);
    this.scheduler = checkNotNull(scheduler);
  }

  @Override
  public TaskFactory getTaskFactory() {
    return taskFactory;
  }

  /**
   * Helper to ensure provided scheduler is non-null.
   */
  private SchedulerSPI getScheduler() {
    SchedulerSPI result = scheduler.get();
    checkState(result != null);
    return result;
  }

  @Override
  public ScheduleFactory getScheduleFactory() {
    ScheduleFactory result = getScheduler().scheduleFactory();
    checkState(result != null);
    return result;
  }

  @Override
  public int getRunningTaskCount() {
    return getScheduler().getRunningTaskCount();
  }

  @Override
  public int getExecutedTaskCount() {
    return getScheduler().getExecutedTaskCount();
  }

  @Override
  public TaskConfiguration createTaskConfigurationInstance(final String typeId) {
    checkNotNull(typeId);

    TaskDescriptor descriptor = taskFactory.findDescriptor(typeId);
    checkArgument(descriptor != null, "Missing descriptor for task with type-id: %s", typeId);

    TaskConfiguration config = descriptor.createTaskConfiguration();
    descriptor.initializeConfiguration(config); // in case any hardcode values need to be inserted
    config.setId(UUID.randomUUID().toString());
    config.setTypeId(descriptor.getId());
    config.setTypeName(descriptor.getName());
    config.setName(descriptor.getName());
    config.setVisible(descriptor.isVisible());
    config.setRecoverable(descriptor.isRecoverable());
    config.setExposed(descriptor.isExposed());
    
    // Set whether this task should use virtual threads based on task characteristics
    config.setUseVirtualThreads(shouldUseVirtualThreads(descriptor));

    return config;
  }

  /**
   * Determines whether a task should use virtual threads based on its characteristics.
   * <p>
   * This method analyzes the task descriptor to determine if the task would benefit
   * from virtual threads. Generally, I/O-bound tasks benefit most from virtual threads,
   * while CPU-intensive tasks may perform better with platform threads.
   * 
   * @param descriptor the task descriptor to evaluate
   * @return true if virtual threads should be used, false otherwise
   */
  protected boolean shouldUseVirtualThreads(TaskDescriptor descriptor) {
    // If virtual threads are globally disabled, don't use them
    if (!useVirtualThreads) {
      return false;
    }
    
    // Check if this task type is known to be CPU-intensive
    // CPU-intensive tasks generally perform better with platform threads
    if (descriptor.getId() != null) {
      String typeId = descriptor.getId();
      
      // Add known CPU-intensive task types here
      if (typeId.contains("rebuild-index") || 
          typeId.contains("compact") || 
          typeId.contains("compute-checksum") ||
          typeId.contains("optimize") ||
          typeId.contains("analyze") ||
          typeId.contains("purge")) {
        log.debug("Task type {} identified as CPU-intensive, using platform threads", typeId);
        return false;
      }
      
      // Tasks that are known to benefit from virtual threads (I/O-bound)
      if (typeId.contains("proxy") ||
          typeId.contains("download") ||
          typeId.contains("fetch") ||
          typeId.contains("sync") ||
          typeId.contains("s3") ||
          typeId.contains("http") ||
          typeId.contains("remote")) {
        log.debug("Task type {} identified as I/O-bound, using virtual threads", typeId);
        return true;
      }
    }
    
    // Default to using virtual threads for most tasks
    return true;
  }

  @Override
  public TaskInfo submit(final TaskConfiguration config) {
    return scheduleTask(config, getScheduleFactory().now());
  }

  @Override
  public TaskInfo getTaskById(final String id) {
    checkNotNull(id);
    TaskInfo taskInfo = getScheduler().getTaskById(id);
    if (null != taskInfo && includeRepoMoveTask(taskInfo)) {
      return taskInfo;
    }
    return null;
  }

  @Override
  public List<TaskInfo> listsTasks() {
    return getScheduler().listsTasks()
        .stream()
        .filter(this::includeRepoMoveTask)
        .collect(Collectors.toList());
  }

  private boolean includeRepoMoveTask(TaskInfo taskInfo) {
    // Use ReentrantLock instead of synchronized to avoid virtual thread pinning
    lock.lock();
    try {
      return (changeRepoBlobstoreTaskEnabled || !taskInfo.getTypeId().equals(REPO_MOVE_TYPE_ID));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public TaskInfo scheduleTask(final TaskConfiguration config, final Schedule schedule) {
    checkNotNull(config);
    checkNotNull(schedule);

    config.validate();

    Date now = new Date();
    if (config.getCreated() == null) {
      config.setCreated(now);
    }
    config.setUpdated(now);

    // Log whether this task will use virtual threads
    if (config.isUseVirtualThreads()) {
      log.debug("Task {} will use virtual threads", config.getTaskLogName());
    }

    TaskInfo taskInfo = getScheduler().scheduleTask(config, schedule);

    log.info("Task {} scheduled: {} (using {})",
        taskInfo.getConfiguration().getTaskLogName(),
        taskInfo.getSchedule().getType(),
        taskInfo.getConfiguration().isUseVirtualThreads() ? "virtual threads" : "platform threads"
    );
    
    // Log diagnostics about potential thread pinning issues
    logThreadPinningDiagnostics(taskInfo);

    eventManager.post(new TaskScheduledEvent(taskInfo));

    return taskInfo;
  }

  @Override
  public ExternalTaskState toExternalTaskState(final TaskInfo taskInfo) {
    return new ExternalTaskState(taskInfo);
  }

  @Override
  public boolean cancel(final String id, final boolean mayInterruptIfRunning) {
    return getScheduler().cancel(id, mayInterruptIfRunning);
  }

  @Nullable
  @Override
  public TaskInfo getTaskByTypeId(final String typeId) {
    return getScheduler().getTaskByTypeId(typeId);
  }

  @Nullable
  @Override
  public TaskInfo getTaskByTypeId(final String typeId, final Map<String, String> config) {
    return getScheduler().getTaskByTypeId(typeId, config);
  }

  @Override
  public boolean findAndSubmit(final String typeId) {
    return getScheduler().findAndSubmit(typeId);
  }

  @Override
  public boolean findWaitingTask(final String typeId, Map<String, String> config) {
    return getScheduler().findWaitingTask(typeId, config);
  }

  @Override
  public boolean findAndSubmit(final String typeId, final Map<String, String> config) {
    return getScheduler().findAndSubmit(typeId, config);
  }
  
  /**
   * Creates a ThreadFactory that produces either virtual threads or platform threads
   * based on the task configuration, with proper security context propagation.
   * <p>
   * This factory ensures that the Apache Shiro security context is properly propagated
   * to the created threads, which is essential for maintaining security across thread boundaries.
   *
   * @param useVirtual whether to use virtual threads
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates the appropriate type of thread with security context
   */
  public static ThreadFactory createThreadFactory(boolean useVirtual, String namePrefix) {
    if (useVirtual) {
      return task -> {
        // Capture the current security subject before creating the thread
        final Subject currentSubject = ThreadContext.getSubject();
        
        // Capture task-specific MDC values to propagate to the virtual thread
        final String taskId = MDC.get("taskId");
        final String taskType = MDC.get("taskType");
        
        // Wrap the task in MDCAwareRunnable to propagate logging context
        Runnable wrappedTask = new MDCAwareRunnable(() -> {
          try {
            // Propagate the security context to the virtual thread
            if (currentSubject != null) {
              ThreadContext.bind(currentSubject);
            }
            
            // Restore task-specific MDC values
            if (taskId != null) {
              MDC.put("taskId", taskId);
            }
            if (taskType != null) {
              MDC.put("taskType", taskType);
            }
            
            // Execute the original task
            task.run();
          } finally {
            // Clean up the thread context and MDC
            ThreadContext.unbindSubject();
            MDC.remove("taskId");
            MDC.remove("taskType");
          }
        });
        
        Thread thread = Thread.ofVirtual()
            .name(namePrefix + "-" + System.nanoTime())
            .unstarted(wrappedTask);
        return thread;
      };
    } else {
      // For platform threads, use the NexusThreadFactory which already handles security context
      return new NexusThreadFactory(namePrefix, namePrefix);
    }
  }
  
  /**
   * Logs diagnostic information about thread pinning issues.
   * This is useful for identifying performance bottlenecks with virtual threads.
   * <p>
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically due to synchronized blocks/methods or native method calls. This can reduce
   * the performance benefits of virtual threads.
   *
   * @param taskInfo the task information to check for pinning issues
   */
  protected void logThreadPinningDiagnostics(TaskInfo taskInfo) {
    if (taskInfo.getConfiguration().isUseVirtualThreads() && threadPinningDiagnostics) {
      // Check if JFR events for virtual thread pinning are enabled
      boolean jfrEventsEnabled = Boolean.getBoolean("jdk.tracePinnedThreads");
      if (!jfrEventsEnabled) {
        VIRTUAL_THREAD_LOGGER.info("Virtual thread pinning diagnostics enabled for task {}. " +
                "Consider adding -Djdk.tracePinnedThreads=full JVM option for detailed diagnostics.",
            taskInfo.getConfiguration().getTaskLogName());
      } else {
        VIRTUAL_THREAD_LOGGER.debug("Virtual thread pinning diagnostics active for task {}",
            taskInfo.getConfiguration().getTaskLogName());
      }
      
      // Log thread statistics
      int threadCount = THREAD_MX_BEAN.getThreadCount();
      long totalStartedThreadCount = THREAD_MX_BEAN.getTotalStartedThreadCount();
      VIRTUAL_THREAD_LOGGER.debug("Current thread statistics - Thread count: {}, Total started: {}", 
          threadCount, totalStartedThreadCount);
      
      // Recommend using JFR for continuous monitoring
      VIRTUAL_THREAD_LOGGER.debug("For production monitoring of thread pinning, consider using JFR events: " +
          "jdk.VirtualThreadPinned with a RecordingStream");
      
      // Log potential synchronized blocks that might cause pinning
      VIRTUAL_THREAD_LOGGER.debug("Common causes of thread pinning include synchronized blocks/methods and native calls. " +
          "Consider using java.util.concurrent.locks.ReentrantLock instead of synchronized.");
    }
  }
}