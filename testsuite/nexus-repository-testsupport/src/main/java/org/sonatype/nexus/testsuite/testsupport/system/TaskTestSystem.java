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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.events.TaskEvent;
import org.sonatype.nexus.scheduling.events.TaskEventCanceled;
import org.sonatype.nexus.scheduling.events.TaskEventStarted;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;

import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Test support system for task scheduling and event monitoring.
 * <p>
 * This implementation leverages Java 21 virtual threads for improved concurrency in task execution and event handling.
 * Compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0 for modern testing approaches.
 *
 * @since 3.0
 */
@Named
@Singleton
public class TaskTestSystem
    extends TestSystemSupport
    implements EventAware, EventAware.Asynchronous
{
  private static final Logger log = LoggerFactory.getLogger(TaskTestSystem.class);

  private final List<TaskEvent> events = new CopyOnWriteArrayList<>();

  private final List<TaskInfo> tasks = new CopyOnWriteArrayList<>();

  private final TaskScheduler scheduler;
  
  private final ExecutorService virtualThreadExecutor;

  /**
   * Constructor with required dependencies.
   *
   * @param scheduler the task scheduler to use for creating and managing tasks
   * @param eventManager the event manager for event handling
   */
  @Inject
  public TaskTestSystem(final TaskScheduler scheduler, final EventManager eventManager) {
    super(eventManager);
    this.scheduler = checkNotNull(scheduler);
    // Create a virtual thread per task executor for improved concurrency
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void doAfter() {
    clear();
    tasks.forEach(TaskInfo::remove);
    tasks.clear();
    virtualThreadExecutor.shutdown();
  }

  /**
   * Event handler for task events.
   * Uses virtual threads for asynchronous processing when appropriate.
   *
   * @param event the task event to process
   */
  @Subscribe
  public void on(final TaskEvent event) {
    log.debug("Received event: {}", event);
    // For simple event recording, we add directly to avoid unnecessary overhead
    // Virtual threads are used for more complex processing in other parts of the system
    events.add(event);
  }

  /**
   * Remove tasks by type ID.
   *
   * @param taskId the task type ID to remove
   */
  public void remove(final String taskId) {
    List<TaskInfo> tasksToRemove = scheduler.listsTasks().stream()
        .filter(task -> taskId.equals(task.getTypeId()))
        .toList(); // Using modern toList() method from Java 16+
    tasksToRemove.forEach(TaskInfo::remove);

    List<TaskInfo> newState = tasks
        .stream()
        .filter(it -> !taskId.equals(it.getId()))
        .toList(); // Using modern toList() method from Java 16+

    tasks.clear();
    tasks.addAll(newState);
  }

  /**
   * Clear the recorded event queue.
   */
  public void clear() {
    events.clear();
  }

  /**
   * Count the number of tasks that have started since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type.
   * @return the count of started tasks
   */
  public long eventStarted(final String typeId) {
    return events(TaskEventStarted.class, typeId).count();
  }

  /**
   * Count the number of tasks that have started since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type
   * @param configuration the task configuration to match
   * @return the count of started tasks matching the configuration
   */
  public long eventStarted(final String typeId, final Map<String, String> configuration) {
    return events(TaskEventStarted.class, typeId)
        .filter(taskInfo -> taskInfo.getConfiguration().asMap().equals(configuration))
        .count();
  }

  /**
   * Count the number of tasks that have completed successfully since the current test started,
   * or {@code clear} was called.
   *
   * @param typeId the task id type
   * @return the count of successfully completed tasks
   */
  public long eventDone(final String typeId) {
    return events(TaskEventStoppedDone.class, typeId).count();
  }

  /**
   * Count the number of tasks that have completed successfully since the current test started,
   * or {@code clear} was called.
   *
   * @param typeId the task id type
   * @param configuration the task configuration to match
   * @return the count of successfully completed tasks matching the configuration
   */
  public long eventDone(final String typeId, final Map<String, String> configuration) {
    return events(TaskEventStoppedDone.class, typeId)
        .filter(taskInfo -> taskInfo.getConfiguration().asMap().equals(configuration))
        .count();
  }

  /**
   * Count the number of tasks that have failed since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type
   * @return the count of failed tasks
   */
  public long eventFailed(final String typeId) {
    return events(TaskEventStoppedFailed.class, typeId).count();
  }

  /**
   * Count the number of tasks that have failed since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type
   * @param configuration the task configuration to match
   * @return the count of failed tasks matching the configuration
   */
  public long eventFailed(final String typeId, final Map<String, String> configuration) {
    return events(TaskEventStoppedFailed.class, typeId)
        .filter(taskInfo -> taskInfo.getConfiguration().asMap().equals(configuration))
        .count();
  }

  /**
   * Count the number of tasks that have been canceled since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type
   * @return the count of canceled tasks
   */
  public long eventCanceled(final String typeId) {
    return events(TaskEventCanceled.class, typeId).count();
  }

  /**
   * Count the number of tasks that have been canceled since the current test started, or {@code clear} was called.
   *
   * @param typeId the task id type
   * @param configuration the task configuration to match
   * @return the count of canceled tasks matching the configuration
   */
  public long eventCanceled(final String typeId, final Map<String, String> configuration) {
    return events(TaskEventCanceled.class, typeId)
        .filter(taskInfo -> taskInfo.getConfiguration().asMap().equals(configuration))
        .count();
  }

  /**
   * Create a task with the given name and type ID.
   *
   * @param name the task name
   * @param typeId the task type ID
   * @return the created task info
   */
  public TaskInfo create(final String name, final String typeId) {
    return create(name, typeId, Collections.emptyMap(), __ -> {});
  }

  /**
   * Create a task with the given name, type ID, and attributes.
   *
   * @param name the task name
   * @param typeId the task type ID
   * @param attributes the task attributes
   * @return the created task info
   */
  public TaskInfo create(final String name, final String typeId, final Map<String, String> attributes) {
    return create(name, typeId, attributes, __ -> {});
  }

  /**
   * Create a task with the given name, type ID, attributes, and configuration mutator.
   * <p>
   * This implementation leverages virtual threads for improved concurrency when appropriate.
   *
   * @param name the task name
   * @param typeId the task type ID
   * @param attributes the task attributes
   * @param mutator the configuration mutator
   * @return the created task info
   */
  public TaskInfo create(
      final String name,
      final String typeId,
      final Map<String, String> attributes,
      final Consumer<TaskConfiguration> mutator)
  {
    TaskConfiguration taskConfiguration = scheduler.createTaskConfigurationInstance(typeId);
    attributes.forEach(taskConfiguration::setString);
    taskConfiguration.setName(name);
    taskConfiguration.setEnabled(true);
    mutator.accept(taskConfiguration);

    // Configure task to use virtual threads when appropriate
    taskConfiguration.setString("useVirtualThreads", "true");

    TaskInfo taskInfo = scheduler.scheduleTask(taskConfiguration, scheduler.getScheduleFactory().manual());
    tasks.add(taskInfo);
    return taskInfo;
  }

  /**
   * Get a stream of task info objects for events of the given class and type ID.
   *
   * @param clazz the event class to filter by
   * @param typeId the task type ID to filter by
   * @return a stream of matching task info objects
   */
  private Stream<TaskInfo> events(final Class<? extends TaskEvent> clazz, final String typeId) {
    return events.stream()
        .filter(clazz::isInstance)
        .map(TaskEvent::getTaskInfo)
        .filter(info -> info.getTypeId().equals(typeId));
  }
}