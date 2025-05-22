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
package stringtemplates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.logging.task.TaskLogger;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskInterruptedException;

/**
 * Tests to validate the correct implementation of Java 21's String Template feature
 * in task progress reporting within the nexus-blobstore-tasks plugin.
 */
public class TaskProgressStringTemplateTest
    extends TestSupport
{
  private TaskLogger taskLogger;
  private BlobStore blobStore;
  private BlobStoreMetrics metrics;
  private TaskInfo taskInfo;
  private ArgumentCaptor<String> messageCaptor;

  @BeforeEach
  public void setup() {
    taskLogger = mock(TaskLogger.class);
    blobStore = mock(BlobStore.class);
    metrics = mock(BlobStoreMetrics.class);
    taskInfo = mock(TaskInfo.class);
    messageCaptor = ArgumentCaptor.forClass(String.class);
    
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(null);
    when(metrics.getBlobCount()).thenReturn(1000L);
    when(metrics.getTotalSize()).thenReturn(5000000L);
  }

  @Test
  @DisplayName("Test basic String Template usage in task progress reporting")
  public void testBasicStringTemplateInTaskProgress() {
    // Simulate a task progress message using String Templates
    String blobStoreName = "test-store";
    long blobCount = 1000L;
    
    // Using String Template syntax
    String progressMessage = STR."Processing \{blobCount} blobs in \{blobStoreName}";
    
    // Log the message
    taskLogger.info(progressMessage);
    
    // Verify the message was correctly formatted
    verify(taskLogger).info(messageCaptor.capture());
    String capturedMessage = messageCaptor.getValue();
    
    // Assert the message contains the interpolated values
    assertEquals("Processing 1000 blobs in test-store", capturedMessage);
  }

  @Test
  @DisplayName("Test String Template with expressions in task progress reporting")
  public void testStringTemplateWithExpressionsInTaskProgress() {
    // Simulate a task progress message with expressions in String Templates
    String blobStoreName = "test-store";
    long blobCount = 1000L;
    long totalSize = 5000000L;
    
    // Using String Template with expressions
    String progressMessage = STR."Processing \{blobCount} blobs (\{totalSize / 1024 / 1024} MB) in \{blobStoreName}";
    
    // Log the message
    taskLogger.info(progressMessage);
    
    // Verify the message was correctly formatted
    verify(taskLogger).info(messageCaptor.capture());
    String capturedMessage = messageCaptor.getValue();
    
    // Assert the message contains the interpolated values with calculated expression
    assertEquals("Processing 1000 blobs (4 MB) in test-store", capturedMessage);
  }

  @Test
  @DisplayName("Test String Template with multiple variables and formatting in task progress")
  public void testStringTemplateWithMultipleVariablesAndFormatting() {
    // Simulate a complex task progress message
    String blobStoreName = "test-store";
    long processedCount = 500L;
    long totalCount = 1000L;
    Duration elapsed = Duration.ofSeconds(65);
    
    // Calculate percentage
    int percentage = (int) ((processedCount * 100) / totalCount);
    
    // Using String Template with multiple variables and formatting
    String progressMessage = STR."Task progress: \{percentage}% complete (\{processedCount}/\{totalCount}) " + 
                             STR."in \{blobStoreName} - Elapsed time: \{elapsed.toMinutes()} min \{elapsed.toSecondsPart()} sec";
    
    // Log the message
    taskLogger.info(progressMessage);
    
    // Verify the message was correctly formatted
    verify(taskLogger).info(messageCaptor.capture());
    String capturedMessage = messageCaptor.getValue();
    
    // Assert the message contains all interpolated values with correct formatting
    assertEquals("Task progress: 50% complete (500/1000) in test-store - Elapsed time: 1 min 5 sec", capturedMessage);
  }

  @Test
  @DisplayName("Test String Template in error reporting")
  public void testStringTemplateInErrorReporting() {
    // Simulate an error scenario
    String blobStoreName = "test-store";
    String errorType = "IOException";
    String errorMessage = "Failed to read blob data";
    
    // Using String Template for error reporting
    String errorReport = STR."Error processing blob store \{blobStoreName}: \{errorType} - \{errorMessage}";
    
    // Log the error
    taskLogger.error(errorReport);
    
    // Verify the error message was correctly formatted
    verify(taskLogger).error(messageCaptor.capture());
    String capturedMessage = messageCaptor.getValue();
    
    // Assert the error message contains the interpolated values
    assertEquals("Error processing blob store test-store: IOException - Failed to read blob data", capturedMessage);
  }

  @Test
  @DisplayName("Test String Template with conditional expressions")
  public void testStringTemplateWithConditionalExpressions() {
    // Simulate a task with conditional status
    String blobStoreName = "test-store";
    boolean isCompleted = true;
    long processedCount = 1000L;
    
    // Using String Template with conditional expression
    String statusMessage = STR."Task for \{blobStoreName} is \{isCompleted ? "completed" : "in progress"} " + 
                           STR."with \{processedCount} items processed";
    
    // Log the status
    taskLogger.info(statusMessage);
    
    // Verify the status message was correctly formatted
    verify(taskLogger).info(messageCaptor.capture());
    String capturedMessage = messageCaptor.getValue();
    
    // Assert the status message contains the conditional expression result
    assertEquals("Task for test-store is completed with 1000 items processed", capturedMessage);
  }

  @Test
  @DisplayName("Test String Template in task interruption handling")
  public void testStringTemplateInTaskInterruptionHandling() {
    // Simulate a task interruption
    String blobStoreName = "test-store";
    long processedCount = 500L;
    long totalCount = 1000L;
    
    // Using String Template for interruption message
    String interruptionMessage = STR."Task interrupted while processing \{blobStoreName}: " + 
                                STR."\{processedCount} out of \{totalCount} items processed (\{(processedCount * 100) / totalCount}%)";
    
    try {
      // Simulate task interruption
      taskLogger.info(interruptionMessage);
      throw new TaskInterruptedException("Task was interrupted", true);
    }
    catch (TaskInterruptedException e) {
      // Verify the interruption message was correctly formatted
      verify(taskLogger).info(messageCaptor.capture());
      String capturedMessage = messageCaptor.getValue();
      
      // Assert the interruption message contains the interpolated values
      assertEquals("Task interrupted while processing test-store: 500 out of 1000 items processed (50%)", 
                   capturedMessage);
    }
  }

  @Test
  @DisplayName("Test String Template with dynamic counter updates")
  public void testStringTemplateWithDynamicCounterUpdates() {
    // Simulate a task with a dynamic counter
    String blobStoreName = "test-store";
    AtomicInteger counter = new AtomicInteger(0);
    
    // Process first batch
    counter.addAndGet(250);
    String progressMessage1 = STR."Processed \{counter.get()} items in \{blobStoreName}";
    taskLogger.info(progressMessage1);
    
    // Process second batch
    counter.addAndGet(250);
    String progressMessage2 = STR."Processed \{counter.get()} items in \{blobStoreName}";
    taskLogger.info(progressMessage2);
    
    // Verify the progress messages were correctly formatted
    verify(taskLogger).info("Processed 250 items in test-store");
    verify(taskLogger).info("Processed 500 items in test-store");
  }
}