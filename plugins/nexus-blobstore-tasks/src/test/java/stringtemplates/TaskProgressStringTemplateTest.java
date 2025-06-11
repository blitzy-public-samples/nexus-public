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
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.scheduling.CurrentState;
import org.sonatype.nexus.scheduling.ExternalTaskState;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskState;

/**
 * Tests to validate the correct implementation of Java 21's String Template feature
 * in task progress reporting within the nexus-blobstore-tasks plugin.
 */
public class TaskProgressStringTemplateTest
    extends TestSupport
{
  private static final String BLOB_STORE_NAME = "test-blobstore";
  
  private BlobStore blobStore;
  private BlobStoreMetrics metrics;
  private TaskInfo taskInfo;
  
  @BeforeEach
  public void setUp() {
    blobStore = mock(BlobStore.class);
    metrics = mock(BlobStoreMetrics.class);
    taskInfo = mock(TaskInfo.class);
    
    when(blobStore.getBlobStoreConfiguration().getName()).thenReturn(BLOB_STORE_NAME);
  }
  
  /**
   * Test that basic progress messages correctly use String Templates for variable interpolation.
   */
  @Test
  @DisplayName("Verify basic progress message with String Templates")
  public void testBasicProgressMessage() {
    // Setup test data
    int processedCount = 1000;
    int totalCount = 5000;
    
    // Create progress message using String Templates
    String progressMessage = STR."Processing \{processedCount} of \{totalCount} blobs in \{BLOB_STORE_NAME}";
    
    // Verify the message contains the interpolated values
    assertEquals("Processing 1000 of 5000 blobs in test-blobstore", progressMessage);
    
    // Verify the message doesn't contain the template syntax
    assertTrue(!progressMessage.contains("\\{") && !progressMessage.contains("}"));
  }
  
  /**
   * Test that status updates with multiple variables correctly use String Templates.
   */
  @Test
  @DisplayName("Verify status update with multiple variables using String Templates")
  public void testStatusUpdateWithMultipleVariables() {
    // Setup test data
    long blobCount = 10000;
    long totalSize = 1024 * 1024 * 1024; // 1 GB
    String status = "RUNNING";
    
    when(metrics.getBlobCount()).thenReturn(blobCount);
    when(metrics.getTotalSize()).thenReturn(totalSize);
    CurrentState localState = mock(CurrentState.class);
    when(localState.getState()).thenReturn(TaskState.RUNNING);
    when(taskInfo.getCurrentState()).thenReturn(localState);
    
    // Create status update using String Templates
    String statusUpdate = STR."Task status: \{status}, processing blob store \{BLOB_STORE_NAME} " +
        STR."with \{blobCount} blobs and total size of \{formatSize(totalSize)}";
    
    // Verify the status update contains the interpolated values
    assertEquals("Task status: RUNNING, processing blob store test-blobstore with 10000 blobs and total size of 1.00 GB", 
        statusUpdate);
  }
  
  /**
   * Test that completion notifications with timing information correctly use String Templates.
   */
  @Test
  @DisplayName("Verify completion notification with timing using String Templates")
  public void testCompletionNotificationWithTiming() {
    // Setup test data
    Duration duration = Duration.ofMinutes(5).plusSeconds(30);
    int processedCount = 5000;
    
    // Create completion notification using String Templates
    String completionMessage = STR."Completed processing \{processedCount} blobs in \{BLOB_STORE_NAME} " +
        STR."(duration: \{formatDuration(duration)})";
    
    // Verify the completion notification contains the interpolated values
    assertEquals("Completed processing 5000 blobs in test-blobstore (duration: 5m 30s)", completionMessage);
  }
  
  /**
   * Test that error messages with exception details correctly use String Templates.
   */
  @Test
  @DisplayName("Verify error message with exception details using String Templates")
  public void testErrorMessageWithExceptionDetails() {
    // Setup test data
    Exception exception = new RuntimeException("Test error message");
    String taskName = "CompactBlobStoreTask";
    
    // Create error message using String Templates
    String errorMessage = STR."Error in task \{taskName} for blob store \{BLOB_STORE_NAME}: \{exception.getMessage()}";
    
    // Verify the error message contains the interpolated values
    assertEquals("Error in task CompactBlobStoreTask for blob store test-blobstore: Test error message", errorMessage);
  }
  
  /**
   * Test that nested expressions in String Templates work correctly.
   */
  @Test
  @DisplayName("Verify nested expressions in String Templates")
  public void testNestedExpressions() {
    // Setup test data
    Map<String, Integer> blobCounts = Map.of(
        "maven-central", 1000,
        "npm-proxy", 500,
        "docker-hosted", 250
    );
    
    // Create message with nested expressions using String Templates
    String message = STR."Blob counts for repositories: " +
        STR."Maven Central: \{blobCounts.get("maven-central")}, " +
        STR."NPM Proxy: \{blobCounts.get("npm-proxy")}, " +
        STR."Docker Hosted: \{blobCounts.get("docker-hosted")}";
    
    // Verify the message contains the interpolated values from nested expressions
    assertEquals("Blob counts for repositories: Maven Central: 1000, NPM Proxy: 500, Docker Hosted: 250", message);
  }
  
  /**
   * Test that multi-line String Templates work correctly.
   */
  @Test
  @DisplayName("Verify multi-line String Templates")
  public void testMultiLineStringTemplates() {
    // Setup test data
    int successCount = 4500;
    int failureCount = 50;
    int skippedCount = 450;
    
    // Create multi-line message using String Templates
    String report = STR."""
        Task Report for Blob Store: \{BLOB_STORE_NAME}
        --------------------------------
        Successful: \{successCount}
        Failed:     \{failureCount}
        Skipped:    \{skippedCount}
        --------------------------------
        Total:      \{successCount + failureCount + skippedCount}
        """;
    
    // Verify the multi-line message contains the interpolated values
    String expected = """
        Task Report for Blob Store: test-blobstore
        --------------------------------
        Successful: 4500
        Failed:     50
        Skipped:    450
        --------------------------------
        Total:      5000
        """;
    
    assertEquals(expected, report);
  }
  
  /**
   * Helper method to format file size in human-readable format.
   */
  private String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    } else if (bytes < 1024 * 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    } else if (bytes < 1024 * 1024 * 1024) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    } else {
      return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
  }
  
  /**
   * Helper method to format duration in human-readable format.
   */
  private String formatDuration(Duration duration) {
    long minutes = duration.toMinutes();
    long seconds = duration.minusMinutes(minutes).getSeconds();
    
    return minutes + "m " + seconds + "s";
  }
}