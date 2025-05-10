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
package org.sonatype.nexus.logging.task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.sonatype.nexus.logging.task.TaskLogType.BOTH;
import static org.sonatype.nexus.logging.task.TaskLogType.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLogType.REPLICATION_LOGGING;
import static org.sonatype.nexus.logging.task.TaskLogType.TASK_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLogType.TASK_LOG_ONLY_WITH_PROGRESS;

/**
 * Tests for {@link TaskLoggerFactory}.
 */
@Category(Java21TestGroup.class)
public class TaskLoggerFactoryTest
{
  @Test
  void bothReturnsExpectedLoggerType() {
    TaskLogger taskLogger = TaskLoggerFactory.create(new Both(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(SeparateTaskLogTaskLogger.class, taskLogger);
  }

  @Test
  void taskLogOnlyReturnsExpectedLoggerType() {
    TaskLogger taskLogger = TaskLoggerFactory.create(new TaskLogOnly(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(TaskLogOnlyTaskLogger.class, taskLogger);
  }

  @Test
  void replicationLoggingReturnsExpectedLoggerType() {
    TaskLogger taskLogger =
        TaskLoggerFactory.create(new ReplicationLogging(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(ReplicationTaskLogger.class, taskLogger);
  }

  @Test
  void taskLogWithProgressReturnsExpectedLoggerType() {
    TaskLogger taskLogger =
        TaskLoggerFactory.create(new TaskLogWithProgress(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(TaskLogWithProgressLogger.class, taskLogger);
  }

  @Test
  void nexusLogOnlyReturnsExpectedLoggerType() {
    TaskLogger taskLogger = TaskLoggerFactory.create(new NexusLogOnly(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(ProgressTaskLogger.class, taskLogger);
  }

  @Test
  void defaultReturnsExpectedLoggerType() {
    TaskLogger taskLogger = TaskLoggerFactory.create(new Object(), mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(SeparateTaskLogTaskLogger.class, taskLogger);
  }
  
  @Test
  void virtualThreadExecutorReturnsExpectedLoggerType() {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Test with an object that has BOTH logging
    Runnable task = new Runnable() {
      @TaskLogging(BOTH)
      public void run() {
        // Task implementation not needed for this test
      }
    };
    
    // Submit the task to the executor (not waiting for execution, just testing factory behavior)
    executor.submit(task);
    
    // Verify the factory creates the expected logger type for the task
    TaskLogger taskLogger = TaskLoggerFactory.create(task, mock(Logger.class), mock(TaskLogInfo.class));
    assertInstanceOf(SeparateTaskLogTaskLogger.class, taskLogger);
    
    // Clean up
    executor.shutdown();
  }

  @TaskLogging(BOTH)
  private static final class Both
  {
  }

  @TaskLogging(TASK_LOG_ONLY)
  private static final class TaskLogOnly
  {
  }

  @TaskLogging(REPLICATION_LOGGING)
  private static final class ReplicationLogging
  {
  }

  @TaskLogging(TASK_LOG_ONLY_WITH_PROGRESS)
  private static final class TaskLogWithProgress
  {
  }

  @TaskLogging(NEXUS_LOG_ONLY)
  private static final class NexusLogOnly
  {
  }
}