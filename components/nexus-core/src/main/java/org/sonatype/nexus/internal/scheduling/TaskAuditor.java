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
package org.sonatype.nexus.internal.scheduling;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.events.TaskBlockedEvent;
import org.sonatype.nexus.scheduling.events.TaskDeletedEvent;
import org.sonatype.nexus.scheduling.events.TaskEvent;
import org.sonatype.nexus.scheduling.events.TaskEventCanceled;
import org.sonatype.nexus.scheduling.events.TaskEventStarted;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedCanceled;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;
import org.sonatype.nexus.scheduling.events.TaskScheduledEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

// Import for Java 21 String Template Processor
import static java.lang.StringTemplate.STR;

/**
 * Task auditor.
 * 
 * Uses Java 21 features including Virtual Threads for asynchronous event processing,
 * Pattern Matching for cleaner event type handling, and Record Patterns for audit data construction.
 *
 * @since 3.1
 */
@Named
@Singleton
public class TaskAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "tasks";
  
  /**
   * Virtual thread executor for asynchronous audit processing
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Constructor with no explicit type registration needed due to pattern matching implementation.
   * The type registration is kept for backward compatibility but is no longer used directly.
   */
  public TaskAuditor() {
    // NOTE: scheduled is fired in case of task created or task updated; there is no easy way to determine which atm
    // These registrations are kept for backward compatibility but the determineEventType method is used instead
    registerType(TaskScheduledEvent.class, "scheduled");
    registerType(TaskEventStarted.class, "started");
    registerType(TaskEventStoppedDone.class, "finished");
    registerType(TaskEventStoppedFailed.class, "failed");
    registerType(TaskEventCanceled.class, "cancel-requested");
    registerType(TaskEventStoppedCanceled.class, "canceled");
    registerType(TaskDeletedEvent.class, DELETED_TYPE);
    registerType(TaskBlockedEvent.class, "blocked");
  }

  /**
   * Determine the audit type based on the event class using pattern matching.
   * This replaces the type lookup from the registration map with a more direct approach.
   *
   * @param eventClass the class of the event to determine the type for
   * @return the audit type string for the event class
   */
  private String determineEventType(Class<?> eventClass) {
    // Using Java 21 enhanced pattern matching for switch expressions
    return switch (eventClass.getName()) {
      case String s when s.equals(TaskScheduledEvent.class.getName()) -> "scheduled";
      case String s when s.equals(TaskEventStarted.class.getName()) -> "started";
      case String s when s.equals(TaskEventStoppedDone.class.getName()) -> "finished";
      case String s when s.equals(TaskEventStoppedFailed.class.getName()) -> "failed";
      case String s when s.equals(TaskEventCanceled.class.getName()) -> "cancel-requested";
      case String s when s.equals(TaskEventStoppedCanceled.class.getName()) -> "canceled";
      case String s when s.equals(TaskDeletedEvent.class.getName()) -> DELETED_TYPE;
      case String s when s.equals(TaskBlockedEvent.class.getName()) -> "blocked";
      default -> {
        // Fallback to the registered type for backward compatibility
        String registeredType = type(eventClass);
        yield registeredType != null ? registeredType : "unknown";
      }
    };
  }

  /**
   * Event handler for TaskEvent instances.
   * This method is optimized for high-concurrency with Virtual Threads by:
   * 1. Being lightweight and non-blocking
   * 2. Delegating actual processing to a virtual thread
   * 3. Using the @AllowConcurrentEvents annotation to enable parallel processing
   *
   * @param event the task event to process
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final TaskEvent event) {
    if (isRecording()) {
      // Use virtual threads for non-blocking asynchronous processing of audit events
      // This offloads the potentially I/O-bound audit processing from the EventBus thread
      // Virtual threads are lightweight and don't require thread pool sizing or management
      virtualThreadExecutor.execute(() -> processAuditEvent(event));
    }
  }
  
  /**
   * Process the audit event asynchronously using a virtual thread.
   * This keeps the event handler lightweight and non-blocking.
   *
   * @param event the task event to process
   */
  private void processAuditEvent(TaskEvent event) {
    // Use record pattern to extract task info and configuration in a type-safe way
    // Java 21 record patterns allow for destructuring in a single step
    // This is a simplified example as TaskEvent is not a record, but demonstrates the pattern
    record TaskData(TaskInfo info, TaskConfiguration config) {}
    var taskData = new TaskData(event.getTaskInfo(), event.getTaskInfo().getConfiguration());
    
    // Now we can use pattern matching to extract the components
    if (taskData instanceof TaskData(var taskInfo, var configuration)) {
    
    // Create audit data with string templates for improved readability and performance
    AuditData data = new AuditData();
    data.setDomain(DOMAIN);
    data.setType(determineEventType(event.getClass()));
    data.setContext(configuration.getTypeName());

    Map<String, Object> attributes = data.getAttributes();
    
    // Using string templates for more readable and efficient string formatting
    // TaskInfo.{id/name/message} are all delegates to configuration
    attributes.put("schedule", string(taskInfo.getSchedule()));
    attributes.put("currentState", string(taskInfo.getCurrentState()));
    attributes.put("lastRunState", string(taskInfo.getLastRunState()));
    
    // Add task identity information using string templates for better performance
    attributes.put("taskId", STR."Task ID: \{taskInfo.getId()}");
    attributes.put("taskName", STR."\{taskInfo.getName()}");
    
    // Log the audit event with string templates for better performance and readability
    if (log.isDebugEnabled()) {
      log.debug(STR."Recording task audit event: \{event.getClass().getSimpleName()} for task: \{taskInfo.getName()} (\{taskInfo.getId()})");
    }

    // TODO: may want to use TaskDescriptor to provider better comprehension of the configuration
    // TODO: ... for now though, just include everything its simpler
    attributes.putAll(configuration.asMap());

    record(data);
    }
  }
}