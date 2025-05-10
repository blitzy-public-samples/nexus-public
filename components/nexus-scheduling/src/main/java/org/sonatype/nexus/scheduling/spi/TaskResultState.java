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

import java.util.Date;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.scheduling.LastRunState;
import org.sonatype.nexus.scheduling.TaskState;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Represents the state of a task result. This class is designed to be immutable, making it inherently thread-safe
 * and suitable for use in concurrent environments including Java 21 virtual threads. The immutability ensures that
 * instances can be safely shared across threads without synchronization concerns.
 * <p>
 * All fields are final and the class provides no methods that modify its state, ensuring consistent behavior
 * in multi-threaded scenarios and preventing thread pinning issues when used with virtual threads.
 */
public class TaskResultState
{
  private final String taskId;

  private final TaskState state;

  private final LastRunState lastRunState;

  private final Date nextFireTime;

  private final String progress;

  /**
   * Creates a new task result state with the specified parameters.
   * <p>
   * This constructor creates an immutable instance that is thread-safe and can be safely used in concurrent
   * environments, including with Java 21 virtual threads. The immutability pattern used here prevents thread
   * pinning issues by avoiding synchronized blocks and ensuring all state is final.
   *
   * @param taskId the task identifier
   * @param state the current state of the task
   * @param nextFireTime the next scheduled execution time, or null if not scheduled
   * @param lastRunState the state from the last run, or null if never run
   * @param progress the current progress information, or null if not available
   */
  public TaskResultState(
      final String taskId,
      final TaskState state,
      @Nullable final Date nextFireTime,
      @Nullable final LastRunState lastRunState,
      @Nullable final String progress)
  {
    this.taskId = checkNotNull(taskId);
    this.state = checkNotNull(state);
    this.nextFireTime = nextFireTime;
    this.lastRunState = lastRunState;
    this.progress = progress;
  }

  /**
   * Returns the next scheduled execution time for the task.
   *
   * @return the next fire time, or null if not scheduled
   */
  public Date getNextFireTime() {
    return nextFireTime;
  }

  /**
   * Returns the state from the last run of the task.
   *
   * @return an Optional containing the last run state, or empty if never run
   */
  public Optional<LastRunState> getLastRunState() {
    return Optional.ofNullable(lastRunState);
  }

  /**
   * Returns the task identifier.
   *
   * @return the task ID
   */
  public String getTaskId() {
    return taskId;
  }

  /**
   * Returns the current state of the task.
   *
   * @return the task state
   */
  public TaskState getState() {
    return state;
  }

  /**
   * Returns the end state from the last run of the task.
   *
   * @return the last end state, or null if never run
   */
  @Nullable
  public TaskState getLastEndState() {
    return getLastRunState()
        .map(LastRunState::getEndState)
        .orElse(null);
  }

  /**
   * Returns the start time of the last run of the task.
   *
   * @return the last run start time, or null if never run
   */
  @Nullable
  public Date getLastRunStarted() {
    return getLastRunState()
        .map(LastRunState::getRunStarted)
        .orElse(null);
  }

  /**
   * Returns the duration of the last run of the task.
   *
   * @return the last run duration in milliseconds, or null if never run
   */
  @Nullable
  public Long getLastRunDuration() {
    return getLastRunState()
        .map(LastRunState::getRunDuration)
        .orElse(null);
  }

  /**
   * Returns the current progress information for the task.
   *
   * @return the progress information, or null if not available
   */
  @Nullable
  public String getProgress() {
    return progress;
  }
  
  /**
   * Indicates whether this task state involves operations that may cause thread pinning.
   * <p>
   * Thread pinning occurs when a virtual thread is forced to remain on its carrier thread,
   * preventing the carrier thread from executing other virtual threads. This typically happens
   * with synchronized blocks or methods, or when using thread-local variables.
   * <p>
   * This implementation returns false because TaskResultState is immutable and contains no operations
   * that would cause thread pinning in a virtual thread environment.
   *
   * @return false as this class does not perform operations that cause thread pinning
   */
  public boolean isThreadPinningRisk() {
    return false;
  }
}