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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TaskLogHome} class.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class TaskLogHomeTest
{
  @Test
  void getTaskLogsHome() {
    Path taskLogHome = Paths.get(TaskLogHome.getTaskLogsHome());
    assertTrue(taskLogHome.endsWith(Paths.get("test", "log", "tasks")));

    Path file = taskLogHome.resolve("temp.log");
    assertFalse(Files.exists(file), "temp file was not deleted");
  }

  @Test
  void getReplicationLogsHome() {
    Path taskLogHome = Paths.get(TaskLogHome.getReplicationLogsHome().get());
    assertTrue(taskLogHome.endsWith(Paths.get("test", "log", "replication")));

    Path file = taskLogHome.resolve("temp.log");
    assertFalse(Files.exists(file), "temp file was not deleted");
  }

  /**
   * Tests that the task log appender is active after starting a task logger and using a temp appender to determine
   * the log path via {@link TaskLogHome#getTaskLogsHome()}
   *
   * Edge case test stemming from NEXUS-13587 and NEXUS-14052
   *
   * @see <a href="https://issues.sonatype.org/browse/NEXUS-13587">NEXUS-13587</a>
   * @see <a href="https://issues.sonatype.org/browse/NEXUS-14052">NEXUS-14052</a>
   */
  @Test
  void testAppenderActiveAfterGetTaskLogHome() throws Exception {
    String timeMillis = String.valueOf(System.currentTimeMillis());
    String taskTypeId = "appendTaskTest".concat(timeMillis);
    String infoLogMsg = "info".concat(timeMillis);
    String errorLogMsg = "error".concat(timeMillis);
    Logger logger = LoggerFactory.getLogger(SeparateTaskLogTaskLogger.class);
    SeparateTaskLogTaskLogger taskLogger = new SeparateTaskLogTaskLogger(logger, createTaskInfo(taskTypeId));

    // log a few messages before getTaskLogHome()
    logger.info("logger initialized");
    logger.error("initialize error");

    // explicitly call target method, start task logger and log a few messages
    TaskLogHome.getTaskLogsHome();
    taskLogger.start();
    logger.info(infoLogMsg);
    logger.error(errorLogMsg, new RuntimeException("runtimeException"));
    taskLogger.finish();

    // validate all messages were written to log file
    String logFileContents = getLogFileContents(taskTypeId);
    assertThat(logFileContents, containsString("logger initialized"));
    assertThat(logFileContents, containsString("initialize error"));
    assertThat(logFileContents, containsString(infoLogMsg));
    assertThat(logFileContents, containsString(errorLogMsg));
    assertThat(logFileContents, containsString("runtimeException"));
  }

  /**
   * Tests concurrent directory creation using virtual threads to ensure thread-safety.
   * This test verifies that multiple threads can safely create and access task log directories.
   */
  @Test
  void concurrentDirectoryCreationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Get task logs home directory
            String taskLogsHome = TaskLogHome.getTaskLogsHome();
            Path taskLogPath = Paths.get(taskLogsHome, "concurrent-test-" + taskId + ".log");
            
            // Create a test file in the directory
            Files.writeString(taskLogPath, "Test content for task " + taskId);
            
            // Verify file exists
            if (Files.exists(taskLogPath)) {
              successCount.incrementAndGet();
              // Clean up
              Files.delete(taskLogPath);
            }
          } catch (Exception e) {
            // Log but don't fail the test - we'll check the success count
            System.err.println("Error in virtual thread task: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete (with timeout)
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Not all virtual threads completed in time");
      
      // Verify all tasks succeeded
      assertTrue(successCount.get() == threadCount, 
          "Expected " + threadCount + " successful operations, but got " + successCount.get());
    } finally {
      executor.shutdown();
    }
  }

  private TaskLogInfo createTaskInfo(final String typeId) {
    return new TaskLogInfo()
    {
      @Override
      public String getId() {
        return "id";
      }

      @Override
      public String getTypeId() {
        return typeId;
      }

      @Override
      public String getName() {
        return "testAppenderTask";
      }

      @Override
      public String getMessage() {
        return "appender test";
      }

      @Nullable
      @Override
      public String getString(final String key) {
        return null;
      }

      @Override
      public boolean getBoolean(final String key, final boolean defaultValue) {
        return false;
      }

      @Override
      public int getInteger(final String key, final int defaultValue) {
        return 0;
      }
    };
  }

  /**
   * Get the contents of the log file for the specified task. Assumes a unique task ID, and will return the contents
   * of the first matching file
   */
  private String getLogFileContents(String typeId) throws IOException {
    Path logDirectory = Paths.get(TaskLogHome.getTaskLogsHome());

    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(logDirectory, String.format("%s-*.log", typeId))) {
      for (Path file : dirStream) {
        if (Files.isRegularFile(file)) {
          return Files.readString(file);
        }
      }
    }

    return "";
  }
}