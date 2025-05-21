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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventAware.Asynchronous;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.scheduling.Task;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskNotificationCondition;
import org.sonatype.nexus.scheduling.TaskNotificationMessageGenerator;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.apache.commons.mail.EmailException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link EventAware} that will send notification email (if necessary) in case of a completed or failed {@link Task}.
 * Uses Java 21 Virtual Threads for asynchronous email sending to improve performance and scalability.
 * Virtual threads are lightweight and efficient for I/O-bound operations like email sending.
 */
@Singleton
@Named
public class NexusTaskNotificationEmailSender
    extends ComponentSupport
    implements EventAware, Asynchronous
{
  private final Provider<EmailManager> emailManager;

  private final Map<String, TaskNotificationMessageGenerator> taskNotificationMessageGenerators;
  
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public NexusTaskNotificationEmailSender(
      final Provider<EmailManager> emailManager,
      final Map<String, TaskNotificationMessageGenerator> taskNotificationMessageGenerators)
  {
    this.emailManager = checkNotNull(emailManager);
    this.taskNotificationMessageGenerators = checkNotNull(taskNotificationMessageGenerators);
    // Create a virtual thread per task executor for efficient handling of I/O-bound operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Sends alert emails if necessary on failure.
   * Uses Virtual Threads for asynchronous processing to prevent blocking the event handler thread.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final TaskEventStoppedFailed event) {
    final TaskInfo taskInfo = event.getTaskInfo();
    if (!haveAlertEmail(taskInfo)) {
      return;
    }
    
    virtualThreadExecutor.submit(() -> {
      String body = taskNotificationMessageGenerator(taskInfo.getTypeId()).failed(taskInfo, event.getFailureCause());
      log.trace(STR."Sending failure notification message for task \{taskInfo.getName()} (\{taskInfo.getTypeId()}): \{body}");
      sendEmail("Task execution failure", taskInfo.getConfiguration().getAlertEmail(), body);
    });
  }

  /**
   * Sends alert emails on completion.
   * Uses Virtual Threads for asynchronous processing to prevent blocking the event handler thread.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final TaskEventStoppedDone event) {
    final TaskInfo taskInfo = event.getTaskInfo();
    if (!haveAlertEmail(taskInfo) ||
        taskInfo.getConfiguration().getNotificationCondition() == TaskNotificationCondition.FAILURE) {
      return;
    }
    
    virtualThreadExecutor.submit(() -> {
      String body = taskNotificationMessageGenerator(taskInfo.getTypeId()).completed(taskInfo);
      log.trace(STR."Sending completion notification message for task \{taskInfo.getName()} (\{taskInfo.getTypeId()}): \{body}");
      sendEmail("Task execution completed", taskInfo.getConfiguration().getAlertEmail(), body);
    });
  }

  /**
   * Checks if the task has a configured alert email address.
   * 
   * @param taskInfo The task information to check
   * @return true if the task has a valid alert email configuration, false otherwise
   */
  private boolean haveAlertEmail(final TaskInfo taskInfo) {
    return taskInfo != null && taskInfo.getConfiguration().getAlertEmail() != null;
  }

  /**
   * Sends an email with the specified subject, address, and body.
   * Uses pattern matching for enhanced exception handling with specific error messages for different exception types.
   */
  private void sendEmail(final String subject, final String address, final String body) {
    try {
      Email mail = new SimpleEmail();
      mail.setSubject(subject);
      mail.addTo(address);
      mail.setMsg(emailManager.get().constructMessage(body));
      emailManager.get().send(mail);
      log.debug(STR."Successfully sent email notification to \{address}");
    }
    catch (Exception e) {
      // Enhanced error handling with Pattern Matching for exceptions
      switch (e) {
        case EmailException emailEx -> log.warn(STR."Failed to send email due to email configuration issue: \{emailEx.getMessage()}", emailEx);
        case NullPointerException npe -> log.warn(STR."Failed to send email due to missing email component: \{npe.getMessage()}", npe);
        case IllegalArgumentException iae -> log.warn(STR."Failed to send email due to invalid argument: \{iae.getMessage()}", iae);
        case InterruptedException ie -> {
          log.warn(STR."Email sending was interrupted: \{ie.getMessage()}", ie);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
        }
        case SecurityException se -> log.warn(STR."Security violation while sending email: \{se.getMessage()}", se);
        default -> log.warn(STR."Failed to send email: \{e.getMessage()}", e);
      }
    }
  }

  /**
   * Gets the appropriate TaskNotificationMessageGenerator for the given task type.
   * Falls back to the default generator if a specific one is not found.
   * 
   * @param typeId The task type ID
   * @return The appropriate TaskNotificationMessageGenerator
   */
  private TaskNotificationMessageGenerator taskNotificationMessageGenerator(final String typeId) {
    TaskNotificationMessageGenerator result = taskNotificationMessageGenerators.get(typeId);
    if (result == null) {
      log.debug(STR."No specific message generator found for task type \{typeId}, using default generator");
      result = taskNotificationMessageGenerators.get(DefaultTaskNotificationMessageGenerator.ID);
    }
    return result;
  }
}