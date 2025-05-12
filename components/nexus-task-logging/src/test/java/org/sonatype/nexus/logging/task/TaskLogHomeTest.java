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
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;

import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TaskLogHome} with Java 21 compatibility.
 */
@Tag("Java21TestGroup")
public class TaskLogHomeTest
    extends TestSupport
{
  @Test
  public void getTaskLogsHome() {
    Path taskLogHome = Paths.get(TaskLogHome.getTaskLogsHome());
    assertTrue(taskLogHome.endsWith(Paths.get("test", "log", "tasks")),
        "Task log home should end with test/log/tasks");

    Path file = taskLogHome.resolve("temp.log");
    assertFalse(Files.exists(file), "temp file was not deleted");
  }

  @Test
  public void getReplicationLogsHome() {
    Path taskLogHome = Paths.get(TaskLogHome.getReplicationLogsHome().get());
    assertTrue(taskLogHome.endsWith(Paths.get("test", "log", "replication")),
        "Replication log home should end with test/log/replication");

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
  public void appenderActiveAfterGetTaskLogHome() throws Exception {
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
    assertThat(logFileContents, StringContains.containsString("logger initialized"));
    assertThat(logFileContents, StringContains.containsString("initialize error"));
    assertThat(logFileContents, StringContains.containsString(infoLogMsg));
    assertThat(logFileContents, StringContains.containsString(errorLogMsg));
    assertThat(logFileContents, StringContains.containsString("runtimeException"));
  }

  /**
   * Tests concurrent directory creation behavior with virtual threads.
   * This verifies that multiple threads can safely create and access the task logs directory.
   */
  @Test
  public void concurrentDirectoryCreationWithVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Use virtual threads for improved concurrency
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Get task logs home directory and create a thread-specific file
            Path taskLogHome = Paths.get(TaskLogHome.getTaskLogsHome());
            Path threadSpecificFile = taskLogHome.resolve("thread-" + threadId + ".log");
            Files.writeString(threadSpecificFile, "Test content from thread " + threadId);
            
            // Verify file was created correctly
            assertTrue(Files.exists(threadSpecificFile), "Thread-specific file should exist");
            assertEquals("Test content from thread " + threadId, 
                Files.readString(threadSpecificFile), "File content should match");
            
            // Clean up
            Files.delete(threadSpecificFile);
            
            completionLatch.countDown();
          }
          catch (Exception e) {
            log.error("Error in virtual thread {}", threadId, e);
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), 
          "All virtual threads should complete within timeout");
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
   * of the first matching file.
   * 
   * Uses virtual threads for file operations to improve I/O concurrency.
   */
  private String getLogFileContents(String typeId) throws IOException {
    Path logDirectory = Paths.get(TaskLogHome.getTaskLogsHome());

    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(logDirectory, String.format("%s-*.log", typeId))) {
      for (Path file : dirStream) {
        if (Files.isRegularFile(file)) {
          // Use virtual thread for file reading
          return Thread.startVirtualThread(() -> {
            try {
              return new String(Files.readAllBytes(file));
            }
            catch (IOException e) {
              throw new RuntimeException("Error reading log file", e);
            }
          }).join();
        }
      }
    }

    return "";
  }
}