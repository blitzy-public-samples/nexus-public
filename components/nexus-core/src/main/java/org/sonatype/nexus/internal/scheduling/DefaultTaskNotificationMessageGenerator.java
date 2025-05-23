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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskNotificationMessageGenerator;
import org.sonatype.nexus.scheduling.TaskState;

import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.time.temporal.ChronoUnit.MILLIS;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Generates notification messages for tasks with no class specific generator.
 * Updated to use Java 21 features including String Templates and Pattern Matching for switch.
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

  @Inject
  public DefaultTaskNotificationMessageGenerator() {
    // No dependencies needed with String Templates
  }

  /**
   * Generates a completion message for a task using String Templates.
   */
  public String completed(final TaskInfo taskInfo) {
    // Format duration using Java 21 features
    String formattedDuration = formatDuration(taskInfo.getLastRunState().getRunDuration());
    
    // Use String Templates instead of Velocity templates
    return STR."Task \{taskInfo.getName()} with ID \{taskInfo.getId()} has completed.\n\n"
        + STR."Started: \{taskInfo.getLastRunState().getRunStarted()}\n"
        + STR."Duration: \{formattedDuration}\n"
        + STR."End State: \{formatEndState(taskInfo.getLastRunState().getEndState())}";
  }

  /**
   * Generates a failure message for a task using String Templates.
   */
  public String failed(final TaskInfo taskInfo, final Throwable cause) {
    // Use String Templates instead of Velocity templates
    String message = STR."Task ID: \{taskInfo.getId()}\nTask Name: \{taskInfo.getName()}";
    
    // Use Pattern Matching for switch to handle the cause
    return switch (cause) {
      case null -> message;
      default -> message + STR."\n\nStack-trace:\n\{ExceptionUtils.getStackTrace(cause)}";
    };
  }
  
  /**
   * Formats the task end state using Pattern Matching for switch.
   */
  private String formatEndState(final TaskState endState) {
    return switch (endState) {
      case TaskState.OK -> "Completed successfully";
      case TaskState.FAILED -> "Failed";
      case TaskState.CANCELED -> "Canceled by user";
      case TaskState.INTERRUPTED -> "Interrupted";
      // Handle other states with a default case for safety
      default -> endState.getDescription();
    };
  }
  
  /**
   * Formats duration in a human-readable format using Java 21 features.
   */
  private String formatDuration(final long durationMillis) {
    Duration duration = Duration.ofMillis(durationMillis);
    
    long hours = duration.toHours();
    long minutes = duration.toMinutesPart();
    long seconds = duration.toSecondsPart();
    long millis = duration.toMillisPart();
    
    // Use String Templates for more readable duration formatting
    return switch {
      case hours > 0 -> STR."\{hours}h \{minutes}m \{seconds}.\{millis}s";
      case minutes > 0 -> STR."\{minutes}m \{seconds}.\{millis}s";
      default -> STR."\{seconds}.\{millis}s";
    };
  }
}