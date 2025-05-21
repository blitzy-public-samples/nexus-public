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

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.scheduling.LastRunState;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskNotificationMessageGenerator;
import org.sonatype.nexus.scheduling.TaskState;

import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.time.temporal.ChronoUnit.MILLIS;

/**
 * Generates notification messages for tasks with no class specific generator.
 * Uses Java 21 String Templates for efficient message formatting.
 *
 * @since 3.22
 */
@Singleton
@Named(DefaultTaskNotificationMessageGenerator.ID)
public class DefaultTaskNotificationMessageGenerator
    extends ComponentSupport
    implements TaskNotificationMessageGenerator
{
  public static final String ID = "DEFAULT";

  /**
   * Generates a completion notification message using String Templates.
   * 
   * @param taskInfo The task information
   * @return Formatted completion message
   */
  public String completed(final TaskInfo taskInfo) {
    LastRunState lastRunState = taskInfo.getLastRunState();
    if (lastRunState == null) {
      return STR."Task \{taskInfo.getName()} with ID \{taskInfo.getId()} has completed with no run state information.";
    }
    
    String formattedDuration = formatDuration(lastRunState.getRunDuration());
    TaskState endState = lastRunState.getEndState();
    
    return STR."Task \{taskInfo.getName()} with ID \{taskInfo.getId()} has completed.\n\n"
        + STR."Started: \{lastRunState.getRunStarted()}\n"
        + STR."Duration: \{formattedDuration}\n"
        + STR."End State: \{formatEndState(endState)}";
  }

  /**
   * Generates a failure notification message using String Templates.
   * 
   * @param taskInfo The task information
   * @param cause The exception that caused the failure
   * @return Formatted failure message
   */
  public String failed(final TaskInfo taskInfo, final Throwable cause) {
    String stackTrace = cause != null ? ExceptionUtils.getStackTrace(cause) : "No stack trace available";
    
    return STR."Task ID: \{taskInfo.getId()}\n"
        + STR."Task Name: \{taskInfo.getName()}\n"
        + STR."Stack-trace:\n\{stackTrace}";
  }
  
  /**
   * Formats the duration in a human-readable format.
   * 
   * @param durationMillis Duration in milliseconds
   * @return Formatted duration string
   */
  private String formatDuration(final long durationMillis) {
    return DateTimeFormatter.ISO_LOCAL_TIME
        .format(LocalTime.MIDNIGHT.plus(durationMillis, MILLIS));
  }
  
  /**
   * Formats the end state with pattern matching for better readability.
   * 
   * @param endState The task end state
   * @return Formatted end state description
   */
  private String formatEndState(final TaskState endState) {
    if (endState == null) {
      return "Unknown";
    }
    
    return switch (endState) {
      case OK -> "Completed successfully";
      case FAILED -> "Failed";
      case CANCELED -> "Canceled by user";
      case INTERRUPTED -> "Interrupted";
      case null -> "Unknown";
      default -> endState.getDescription();
    };
  }
}
