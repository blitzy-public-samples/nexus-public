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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.events.TaskScheduledEvent;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;
import org.sonatype.nexus.thread.NexusExecutorService;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.app.FeatureFlags.CHANGE_REPO_BLOBSTORE_TASK_ENABLED_NAMED;

/**
 * Default {@link TaskScheduler} implementation.
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
   * Task types that are known to be I/O-bound and benefit from Virtual Threads.
   * These tasks typically involve network operations, file system access, or database operations.
   */
  private static final Set<String> IO_BOUND_TASK_TYPES = Set.of(
      "repository.docker.upload-purge",
      "repository.docker.v1-upload-purge",
      "repository.maven.purge-unused-snapshots",
      "repository.maven.rebuild-metadata",
      "repository.maven.unpublish-snapshots",
      "repository.maven.remove-snapshots",
      "repository.purge-unused",
      "blobstore.compact",
      "blobstore.rebuildComponentDB",
      "create.browse.nodes",
      "db.backup",
      "db.rebuild",
      "repository.cleanup",
      "repository.rebuild-index",
      "repository.storage-facet-cleanup",
      "repository.cleanup-local-content",
      "repository.delete-content",
      "repository.move",
      "repository.purge-unused",
      "repository.rebuild-index",
      "repository.storage-facet-cleanup",
      "script",
      "tasklog.cleanup"
  );

  /**
   * Task types that are known to be CPU-bound and should use platform threads.
   * These tasks typically involve heavy computation, compression, or encryption.
   */
  private static final Set<String> CPU_BOUND_TASK_TYPES = Set.of(
      "repository.vulnerability.assessment",
      "security.purge-api-keys",
      "analytics.compute"
  );

  /**
   * Tracks tasks that have been detected as causing thread pinning issues.
   * This helps avoid repeatedly logging the same issues for recurring tasks.
   */
  private final Set<String> threadPinningDetected = ConcurrentHashMap.newKeySet();

  protected static final String REPO_MOVE_TYPE_ID = "repository.move";

  private final EventManager eventManager;

  private final TaskFactory taskFactory;

  private final Provider<SchedulerSPI> scheduler;

  @Inject
  @Named(CHANGE_REPO_BLOBSTORE_TASK_ENABLED_NAMED)
  protected boolean changeRepoBlobstoreTaskEnabled;

  /**
   * Controls whether to use Virtual Threads for suitable tasks.
   * This can be disabled for troubleshooting or in environments where Virtual Threads
   * might cause issues with certain JVM or system configurations.
   */
  @Inject
  @Named("${nexus.tasks.useVirtualThreads:-true}")
  protected boolean useVirtualThreads;

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

    // Set the thread type preference based on the task type
    if (shouldUseVirtualThreads(descriptor.getId())) {
      config.setString("threadType", "virtual");
    } else {
      config.setString("threadType", "platform");
    }

    return config;
  }

  /**
   * Determines if a task should use Virtual Threads based on its type and configuration.
   * 
   * @param typeId the task type identifier
   * @return true if the task should use Virtual Threads, false otherwise
   */
  protected boolean shouldUseVirtualThreads(final String typeId) {
    // If Virtual Threads are disabled globally, always return false
    if (!useVirtualThreads) {
      return false;
    }
    
    // If the task type is known to be I/O-bound, use Virtual Threads
    if (IO_BOUND_TASK_TYPES.contains(typeId)) {
      return true;
    }
    
    // If the task type is known to be CPU-bound, use platform threads
    if (CPU_BOUND_TASK_TYPES.contains(typeId)) {
      return false;
    }
    
    // For unknown task types, default to platform threads for safety
    // This can be overridden by explicitly setting threadType in the task configuration
    return false;
  }

  /**
   * Checks if a task is experiencing thread pinning issues and logs appropriate warnings.
   * Thread pinning occurs when a Virtual Thread is blocked on a native method that doesn't
   * release the carrier thread, preventing the JVM from efficiently multiplexing Virtual Threads.
   * 
   * @param taskInfo the task information to check
   */
  protected void checkForThreadPinning(final TaskInfo taskInfo) {
    if (!useVirtualThreads) {
      return;
    }
    
    TaskConfiguration config = taskInfo.getConfiguration();
    String taskId = config.getId();
    String typeId = config.getTypeId();
    
    // Only check for thread pinning on tasks using Virtual Threads
    if ("virtual".equals(config.getString("threadType", "platform"))) {
      // Check if this task has been running for an unusually long time
      CurrentState state = taskInfo.getCurrentState();
      if (state.getState() == TaskState.RUNNING) {
        long runningTime = System.currentTimeMillis() - state.getRunStarted().getTime();
        long expectedDuration = config.getLong("expectedDurationMillis", 0);
        
        // If the task has been running for significantly longer than expected
        // and we haven't already logged a warning for this task
        if (expectedDuration > 0 && runningTime > expectedDuration * 2 && 
            !threadPinningDetected.contains(taskId)) {
          log.warn("Possible thread pinning detected in task {} ({}). Task has been running for {} ms, " +
              "which is significantly longer than expected. Consider switching to platform threads by " +
              "setting threadType=platform in the task configuration.", 
              config.getName(), typeId, runningTime);
          
          // Remember that we've detected pinning for this task to avoid log spam
          threadPinningDetected.add(taskId);
        }
      }
    }
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
      // Check for thread pinning issues when retrieving task information
      checkForThreadPinning(taskInfo);
      return taskInfo;
    }
    return null;
  }

  @Override
  public List<TaskInfo> listsTasks() {
    List<TaskInfo> tasks = getScheduler().listsTasks()
        .stream()
        .filter(this::includeRepoMoveTask)
        .collect(Collectors.toList());
    
    // Check all running tasks for potential thread pinning issues
    tasks.forEach(this::checkForThreadPinning);
    
    return tasks;
  }

  private boolean includeRepoMoveTask(TaskInfo taskInfo) {
    return (changeRepoBlobstoreTaskEnabled || !taskInfo.getTypeId().equals(REPO_MOVE_TYPE_ID));
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
    
    // If threadType is not set, determine whether to use Virtual Threads based on task type
    if (!config.containsKey("threadType")) {
      if (shouldUseVirtualThreads(config.getTypeId())) {
        config.setString("threadType", "virtual");
        log.debug("Task {} will use Virtual Threads for improved I/O performance", 
            config.getTaskLogName());
      } else {
        config.setString("threadType", "platform");
      }
    }

    TaskInfo taskInfo = getScheduler().scheduleTask(config, schedule);

    log.info("Task {} scheduled: {} (using {} threads)",
        taskInfo.getConfiguration().getTaskLogName(),
        taskInfo.getSchedule().getType(),
        taskInfo.getConfiguration().getString("threadType", "platform")
    );

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
}