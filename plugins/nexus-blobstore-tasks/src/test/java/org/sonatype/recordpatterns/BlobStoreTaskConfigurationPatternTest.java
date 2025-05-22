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
package org.sonatype.recordpatterns;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test class demonstrating how Java 21's Record Pattern feature can be used to simplify
 * and improve the processing of task configuration data in blobstore tasks.
 * 
 * Record Patterns allow for destructuring record values in pattern matching contexts,
 * making it easier to extract components from nested data structures in a type-safe manner.
 */
public class BlobStoreTaskConfigurationPatternTest
    extends TestSupport
{
  // Define records to represent task configuration structures
  record TaskConfig(String blobStoreName, boolean dryRun, boolean integrityCheck) {}
  
  record RestoreConfig(TaskConfig base, boolean restoreBlobs, boolean undeleteBlobs, int sinceDays) {}
  
  record CompactConfig(TaskConfig base, int concurrentProcesses) {}
  
  record BlobStoreTaskInfo(String taskId, String taskName, Object taskConfig) {}
  
  private TaskConfiguration restoreTaskConfig;
  private TaskConfiguration compactTaskConfig;
  
  @Before
  public void setup() {
    // Setup restore task configuration
    restoreTaskConfig = new TaskConfiguration();
    restoreTaskConfig.setId("restore-task");
    restoreTaskConfig.setTypeId("restore-blobstore");
    restoreTaskConfig.setString(".name", "Restore BlobStore Task");
    restoreTaskConfig.setString("blobStoreName", "test-blobstore");
    restoreTaskConfig.setBoolean("dryRun", false);
    restoreTaskConfig.setBoolean("integrityCheck", true);
    restoreTaskConfig.setBoolean("restoreBlobs", true);
    restoreTaskConfig.setBoolean("undeleteBlobs", false);
    restoreTaskConfig.setInteger("sinceDays", 7);
    
    // Setup compact task configuration
    compactTaskConfig = new TaskConfiguration();
    compactTaskConfig.setId("compact-task");
    compactTaskConfig.setTypeId("compact-blobstore");
    compactTaskConfig.setString(".name", "Compact BlobStore Task");
    compactTaskConfig.setString("blobStoreName", "test-blobstore");
    compactTaskConfig.setBoolean("dryRun", true);
    compactTaskConfig.setBoolean("integrityCheck", false);
    compactTaskConfig.setInteger("concurrentProcesses", 4);
  }
  
  /**
   * Demonstrates the traditional way of extracting configuration values from a TaskConfiguration.
   * This approach requires multiple lines of code and explicit type casting.
   */
  @Test
  public void testTraditionalConfigurationExtraction() {
    // Extract values from restore task configuration
    String blobStoreName = restoreTaskConfig.getString("blobStoreName");
    boolean dryRun = restoreTaskConfig.getBoolean("dryRun", false);
    boolean integrityCheck = restoreTaskConfig.getBoolean("integrityCheck", false);
    boolean restoreBlobs = restoreTaskConfig.getBoolean("restoreBlobs", false);
    boolean undeleteBlobs = restoreTaskConfig.getBoolean("undeleteBlobs", false);
    int sinceDays = restoreTaskConfig.getInteger("sinceDays", -1);
    
    // Validate extracted values
    assertEquals("test-blobstore", blobStoreName);
    assertEquals(false, dryRun);
    assertEquals(true, integrityCheck);
    assertEquals(true, restoreBlobs);
    assertEquals(false, undeleteBlobs);
    assertEquals(7, sinceDays);
    
    // Create configuration objects manually
    TaskConfig baseConfig = new TaskConfig(blobStoreName, dryRun, integrityCheck);
    RestoreConfig restoreConfig = new RestoreConfig(baseConfig, restoreBlobs, undeleteBlobs, sinceDays);
    
    // Validate configuration objects
    assertEquals("test-blobstore", restoreConfig.base().blobStoreName());
    assertEquals(false, restoreConfig.base().dryRun());
    assertEquals(true, restoreConfig.base().integrityCheck());
    assertEquals(true, restoreConfig.restoreBlobs());
    assertEquals(false, restoreConfig.undeleteBlobs());
    assertEquals(7, restoreConfig.sinceDays());
  }
  
  /**
   * Demonstrates how to use Record Patterns to simplify configuration extraction.
   * This approach is more concise and type-safe.
   */
  @Test
  public void testRecordPatternConfigurationExtraction() {
    // Convert TaskConfiguration to our record types
    RestoreConfig config = convertToRestoreConfig(restoreTaskConfig);
    
    // Use record pattern with instanceof to extract values directly
    if (config instanceof RestoreConfig(TaskConfig(var blobStoreName, var dryRun, var integrityCheck), 
                                       var restoreBlobs, var undeleteBlobs, var sinceDays)) {
      // Validate extracted values
      assertEquals("test-blobstore", blobStoreName);
      assertEquals(false, dryRun);
      assertEquals(true, integrityCheck);
      assertEquals(true, restoreBlobs);
      assertEquals(false, undeleteBlobs);
      assertEquals(7, sinceDays);
      
      // We can use the extracted variables directly without accessing through the record
      String logMessage = String.format(
          "Processing restore task for blobstore '%s' with integrity check %s and restore blobs %s",
          blobStoreName, integrityCheck, restoreBlobs);
      
      assertThat(logMessage, notNullValue());
      assertTrue(logMessage.contains("test-blobstore"));
      assertTrue(logMessage.contains("integrity check true"));
      assertTrue(logMessage.contains("restore blobs true"));
    }
  }
  
  /**
   * Demonstrates how to use Record Patterns with switch expressions for more powerful pattern matching.
   * This approach allows for concise handling of different task types.
   */
  @Test
  public void testRecordPatternWithSwitchExpression() {
    // Create task info objects
    BlobStoreTaskInfo restoreTaskInfo = createTaskInfo(restoreTaskConfig);
    BlobStoreTaskInfo compactTaskInfo = createTaskInfo(compactTaskConfig);
    
    // Process restore task using switch expression with record patterns
    String restoreResult = processTaskWithSwitch(restoreTaskInfo);
    assertThat(restoreResult, is("Restore task for test-blobstore with 7 days history"));
    
    // Process compact task using switch expression with record patterns
    String compactResult = processTaskWithSwitch(compactTaskInfo);
    assertThat(compactResult, is("Compact task for test-blobstore with 4 concurrent processes"));
  }
  
  /**
   * Demonstrates how to use nested Record Patterns to extract values from complex nested structures.
   * This approach allows for deep pattern matching in a single operation.
   */
  @Test
  public void testNestedRecordPatterns() {
    // Create task info objects with nested configurations
    BlobStoreTaskInfo restoreTaskInfo = createTaskInfo(restoreTaskConfig);
    
    // Extract duration from sinceDays using nested record patterns
    Optional<Duration> duration = extractDurationFromTask(restoreTaskInfo);
    
    assertTrue(duration.isPresent());
    assertEquals(Duration.ofDays(7), duration.get());
  }
  
  /**
   * Helper method to convert TaskConfiguration to RestoreConfig record.
   */
  private RestoreConfig convertToRestoreConfig(TaskConfiguration config) {
    TaskConfig baseConfig = new TaskConfig(
        config.getString("blobStoreName"),
        config.getBoolean("dryRun", false),
        config.getBoolean("integrityCheck", false)
    );
    
    return new RestoreConfig(
        baseConfig,
        config.getBoolean("restoreBlobs", false),
        config.getBoolean("undeleteBlobs", false),
        config.getInteger("sinceDays", -1)
    );
  }
  
  /**
   * Helper method to convert TaskConfiguration to CompactConfig record.
   */
  private CompactConfig convertToCompactConfig(TaskConfiguration config) {
    TaskConfig baseConfig = new TaskConfig(
        config.getString("blobStoreName"),
        config.getBoolean("dryRun", false),
        config.getBoolean("integrityCheck", false)
    );
    
    return new CompactConfig(
        baseConfig,
        config.getInteger("concurrentProcesses", 1)
    );
  }
  
  /**
   * Helper method to create a BlobStoreTaskInfo from a TaskConfiguration.
   */
  private BlobStoreTaskInfo createTaskInfo(TaskConfiguration config) {
    String taskId = config.getId();
    String taskName = config.getString(".name");
    Object taskConfig;
    
    if ("restore-blobstore".equals(config.getTypeId())) {
      taskConfig = convertToRestoreConfig(config);
    } else if ("compact-blobstore".equals(config.getTypeId())) {
      taskConfig = convertToCompactConfig(config);
    } else {
      taskConfig = Map.of("blobStoreName", config.getString("blobStoreName"));
    }
    
    return new BlobStoreTaskInfo(taskId, taskName, taskConfig);
  }
  
  /**
   * Process a task using switch expression with record patterns.
   * This demonstrates how to use record patterns with switch expressions for powerful pattern matching.
   */
  private String processTaskWithSwitch(BlobStoreTaskInfo taskInfo) {
    return switch (taskInfo.taskConfig()) {
      case RestoreConfig(TaskConfig(var blobStoreName, var dryRun, var integrityCheck), 
                        var restoreBlobs, var undeleteBlobs, var sinceDays) ->
          String.format("Restore task for %s with %d days history", blobStoreName, sinceDays);
          
      case CompactConfig(TaskConfig(var blobStoreName, var dryRun, var integrityCheck), 
                        var concurrentProcesses) ->
          String.format("Compact task for %s with %d concurrent processes", 
                       blobStoreName, concurrentProcesses);
                       
      default -> "Unknown task type";
    };
  }
  
  /**
   * Extract Duration from a task using nested record patterns.
   * This demonstrates how to use nested record patterns to extract values from complex nested structures.
   */
  private Optional<Duration> extractDurationFromTask(BlobStoreTaskInfo taskInfo) {
    if (taskInfo.taskConfig() instanceof RestoreConfig(TaskConfig(var blobStoreName, var dryRun, var integrityCheck), 
                                                    var restoreBlobs, var undeleteBlobs, var sinceDays) 
        && sinceDays > 0) {
      return Optional.of(Duration.ofDays(sinceDays));
    }
    return Optional.empty();
  }
}