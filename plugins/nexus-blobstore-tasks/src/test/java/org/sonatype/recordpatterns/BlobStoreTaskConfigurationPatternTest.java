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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class demonstrating Java 21's Record Pattern feature for processing task configuration data
 * in blobstore tasks. Shows how nested record patterns can extract configuration values in a type-safe
 * manner, reducing boilerplate code and improving readability.
 */
public class BlobStoreTaskConfigurationPatternTest
    extends TestSupport
{
  // Constants for task configuration keys
  private static final String BLOB_STORE_NAME_FIELD_ID = "blobStoreName";
  private static final String RESTORE_BLOBS = "restoreBlobs";
  private static final String UNDELETE_BLOBS = "undeleteBlobs";
  private static final String INTEGRITY_CHECK = "integrityCheck";
  private static final String DRY_RUN = "dryRun";
  private static final String SINCE_DAYS = "sinceDays";
  private static final String TASK_ENABLED = "enabled";
  private static final String TASK_NAME = "name";
  private static final String TASK_TYPE_ID = "typeId";
  private static final String TASK_ALERT_EMAIL = "alertEmail";
  private static final String TASK_NOTIFICATION_CONDITION = "notificationCondition";
  
  /**
   * Record representing basic task configuration parameters.
   * This demonstrates how records can be used to model configuration data.
   */
  record TaskConfig(String name, String typeId, boolean enabled, String alertEmail) {}
  
  /**
   * Record representing blobstore-specific task configuration.
   */
  record BlobStoreConfig(String blobStoreName, boolean restoreBlobs, boolean undeleteBlobs, 
                         boolean integrityCheck, boolean dryRun, int sinceDays) {}
  
  /**
   * Record representing task schedule configuration.
   */
  record ScheduleConfig(String type, ZonedDateTime startTime, Duration interval) {}
  
  /**
   * Composite record that combines multiple configuration aspects.
   * This demonstrates nested record structures for complex configurations.
   */
  record CompleteBlobStoreTaskConfig(TaskConfig taskConfig, BlobStoreConfig blobStoreConfig, 
                                    ScheduleConfig scheduleConfig) {}

  /**
   * Test demonstrating basic record pattern usage with TaskConfiguration.
   * Shows how to extract configuration values using record patterns.
   */
  @Test
  public void testBasicRecordPatternWithTaskConfiguration() {
    // Create and populate a task configuration
    TaskConfiguration config = createBasicTaskConfiguration();
    
    // Traditional approach: extract values one by one
    String name = config.getString(TASK_NAME);
    String typeId = config.getString(TASK_TYPE_ID);
    boolean enabled = config.getBoolean(TASK_ENABLED, false);
    String alertEmail = config.getString(TASK_ALERT_EMAIL);
    
    // Verify extracted values
    assertEquals("Test Task", name);
    assertEquals("test-type", typeId);
    assertTrue(enabled);
    assertEquals("admin@example.com", alertEmail);
    
    // Using record pattern to extract configuration values
    // This demonstrates how record patterns can simplify data extraction
    if (config instanceof Map<String, ?> map) {
      // Create a record from the configuration map
      TaskConfig taskConfig = new TaskConfig(
          (String) map.get(TASK_NAME),
          (String) map.get(TASK_TYPE_ID),
          (Boolean) map.getOrDefault(TASK_ENABLED, false),
          (String) map.get(TASK_ALERT_EMAIL));
      
      // Use record pattern to extract components
      if (taskConfig instanceof TaskConfig(String taskName, String taskTypeId, boolean taskEnabled, String email)) {
        // Verify extracted values
        assertEquals("Test Task", taskName);
        assertEquals("test-type", taskTypeId);
        assertTrue(taskEnabled);
        assertEquals("admin@example.com", email);
        
        // Demonstrate direct use of extracted values
        String formattedConfig = formatTaskConfig(taskName, taskTypeId, taskEnabled, email);
        assertThat(formattedConfig, notNullValue());
        log.info("Formatted config: {}", formattedConfig);
      }
    }
  }

  /**
   * Test demonstrating nested record patterns for complex configurations.
   * Shows how to extract and process hierarchical configuration data.
   */
  @Test
  public void testNestedRecordPatternsWithTaskConfiguration() {
    // Create and populate a complete task configuration
    TaskConfiguration config = createCompleteTaskConfiguration();
    
    // Extract configuration components and create nested records
    if (config instanceof Map<String, ?> map) {
      // Create task config record
      TaskConfig taskConfig = new TaskConfig(
          (String) map.get(TASK_NAME),
          (String) map.get(TASK_TYPE_ID),
          (Boolean) map.getOrDefault(TASK_ENABLED, false),
          (String) map.get(TASK_ALERT_EMAIL));
      
      // Create blobstore config record
      BlobStoreConfig blobStoreConfig = new BlobStoreConfig(
          (String) map.get(BLOB_STORE_NAME_FIELD_ID),
          (Boolean) map.getOrDefault(RESTORE_BLOBS, false),
          (Boolean) map.getOrDefault(UNDELETE_BLOBS, false),
          (Boolean) map.getOrDefault(INTEGRITY_CHECK, false),
          (Boolean) map.getOrDefault(DRY_RUN, false),
          (Integer) map.getOrDefault(SINCE_DAYS, -1));
      
      // Create schedule config record
      ScheduleConfig scheduleConfig = new ScheduleConfig(
          "manual",
          ZonedDateTime.now(ZoneId.systemDefault()),
          Duration.ofHours(24));
      
      // Create composite config record
      CompleteBlobStoreTaskConfig completeConfig = 
          new CompleteBlobStoreTaskConfig(taskConfig, blobStoreConfig, scheduleConfig);
      
      // Use nested record pattern to extract all components at once
      if (completeConfig instanceof CompleteBlobStoreTaskConfig(
          TaskConfig(String name, String typeId, boolean enabled, var email),
          BlobStoreConfig(String blobStore, boolean restore, var undelete, var integrity, var dryRun, var sinceDays),
          ScheduleConfig(String scheduleType, var startTime, var interval))) {
        
        // Verify task config values
        assertEquals("Test Task", name);
        assertEquals("test-type", typeId);
        assertTrue(enabled);
        
        // Verify blobstore config values
        assertEquals("test-blob-store", blobStore);
        assertTrue(restore);
        assertTrue(undelete);
        assertFalse(integrity);
        assertTrue(dryRun);
        assertEquals(7, sinceDays);
        
        // Verify schedule config values
        assertEquals("manual", scheduleType);
        assertEquals(Duration.ofHours(24), interval);
        
        // Demonstrate direct use of extracted values
        String taskSummary = generateTaskSummary(name, blobStore, restore, undelete, sinceDays);
        assertThat(taskSummary, notNullValue());
        log.info("Task summary: {}", taskSummary);
      }
    }
  }

  /**
   * Test demonstrating pattern matching in switch expressions.
   * Shows how record patterns can be used with switch for more concise code.
   */
  @Test
  public void testPatternMatchingInSwitchWithTaskConfiguration() {
    // Create different task configurations
    TaskConfiguration restoreConfig = createRestoreTaskConfiguration();
    TaskConfiguration compactConfig = createCompactTaskConfiguration();
    TaskConfiguration integrityConfig = createIntegrityCheckTaskConfiguration();
    
    // Process each configuration using switch pattern matching
    String restoreResult = processTaskConfiguration(restoreConfig);
    String compactResult = processTaskConfiguration(compactConfig);
    String integrityResult = processTaskConfiguration(integrityConfig);
    
    // Verify results
    assertEquals("Restore task for blob store: test-restore-store", restoreResult);
    assertEquals("Compact task for blob store: test-compact-store", compactResult);
    assertEquals("Integrity check task for blob store: test-integrity-store", integrityResult);
  }

  /**
   * Test comparing traditional approach vs. record pattern approach.
   * Demonstrates the readability and conciseness benefits of record patterns.
   */
  @Test
  public void testComparisonOfTraditionalVsRecordPatternApproach() {
    // Create a task configuration
    TaskConfiguration config = createCompleteTaskConfiguration();
    
    // Traditional approach - verbose and requires multiple steps
    String blobStoreName = config.getString(BLOB_STORE_NAME_FIELD_ID);
    boolean restoreBlobs = config.getBoolean(RESTORE_BLOBS, false);
    boolean undeleteBlobs = config.getBoolean(UNDELETE_BLOBS, false);
    boolean integrityCheck = config.getBoolean(INTEGRITY_CHECK, false);
    boolean dryRun = config.getBoolean(DRY_RUN, false);
    int sinceDays = config.getInteger(SINCE_DAYS, -1);
    
    // Verify traditional approach results
    assertEquals("test-blob-store", blobStoreName);
    assertTrue(restoreBlobs);
    assertTrue(undeleteBlobs);
    assertFalse(integrityCheck);
    assertTrue(dryRun);
    assertEquals(7, sinceDays);
    
    // Record pattern approach - more concise and type-safe
    if (config instanceof Map<String, ?> map) {
      BlobStoreConfig blobStoreConfig = new BlobStoreConfig(
          (String) map.get(BLOB_STORE_NAME_FIELD_ID),
          (Boolean) map.getOrDefault(RESTORE_BLOBS, false),
          (Boolean) map.getOrDefault(UNDELETE_BLOBS, false),
          (Boolean) map.getOrDefault(INTEGRITY_CHECK, false),
          (Boolean) map.getOrDefault(DRY_RUN, false),
          (Integer) map.getOrDefault(SINCE_DAYS, -1));
      
      // Use record pattern to extract all components at once
      if (blobStoreConfig instanceof BlobStoreConfig(var name, var restore, var undelete, 
                                                   var integrity, var dry, var days)) {
        // Verify record pattern approach results
        assertEquals("test-blob-store", name);
        assertTrue(restore);
        assertTrue(undelete);
        assertFalse(integrity);
        assertTrue(dry);
        assertEquals(7, days);
        
        // Demonstrate how record patterns make code more readable
        String configSummary = String.format(
            "BlobStore: %s, Restore: %b, Undelete: %b, Integrity: %b, DryRun: %b, SinceDays: %d",
            name, restore, undelete, integrity, dry, days);
        
        log.info("Config summary using record pattern: {}", configSummary);
      }
    }
  }

  /**
   * Helper method to process task configuration using switch pattern matching.
   * Demonstrates how record patterns can be used with switch expressions.
   */
  private String processTaskConfiguration(TaskConfiguration config) {
    if (config instanceof Map<String, ?> map) {
      // Extract type and blobstore name
      String typeId = (String) map.get(TASK_TYPE_ID);
      String blobStoreName = (String) map.get(BLOB_STORE_NAME_FIELD_ID);
      
      // Create a simple record to hold the task type and blobstore name
      record TaskTypeConfig(String typeId, String blobStoreName) {}
      
      // Create the record instance
      TaskTypeConfig taskTypeConfig = new TaskTypeConfig(typeId, blobStoreName);
      
      // Use switch expression with record pattern matching
      return switch (taskTypeConfig) {
        case TaskTypeConfig("restore-task", var store) -> 
            "Restore task for blob store: " + store;
            
        case TaskTypeConfig("compact-task", var store) -> 
            "Compact task for blob store: " + store;
            
        case TaskTypeConfig("integrity-check-task", var store) -> 
            "Integrity check task for blob store: " + store;
            
        case TaskTypeConfig(var type, var store) -> 
            "Unknown task type: " + type + " for blob store: " + store;
      };
    }
    
    return "Invalid task configuration";
  }

  /**
   * Helper method to format task configuration.
   */
  private String formatTaskConfig(String name, String typeId, boolean enabled, String email) {
    return String.format("Task: %s, Type: %s, Enabled: %b, Email: %s",
        name, typeId, enabled, email);
  }

  /**
   * Helper method to generate a task summary.
   */
  private String generateTaskSummary(String name, String blobStore, boolean restore, 
                                    boolean undelete, int sinceDays) {
    StringBuilder summary = new StringBuilder();
    summary.append("Task '")
           .append(name)
           .append("' will process blob store '")
           .append(blobStore)
           .append("'")
           .append(restore ? " with restore" : " without restore")
           .append(undelete ? " and undelete" : " without undelete");
    
    if (sinceDays > 0) {
      summary.append(" for blobs updated in the last ")
             .append(sinceDays)
             .append(" days");
    } else {
      summary.append(" for all blobs");
    }
    
    return summary.toString();
  }

  /**
   * Helper method to create a basic task configuration.
   */
  private TaskConfiguration createBasicTaskConfiguration() {
    TaskConfiguration config = new TaskConfiguration();
    config.setString(TASK_NAME, "Test Task");
    config.setString(TASK_TYPE_ID, "test-type");
    config.setBoolean(TASK_ENABLED, true);
    config.setString(TASK_ALERT_EMAIL, "admin@example.com");
    return config;
  }

  /**
   * Helper method to create a complete task configuration with all parameters.
   */
  private TaskConfiguration createCompleteTaskConfiguration() {
    TaskConfiguration config = createBasicTaskConfiguration();
    config.setString(BLOB_STORE_NAME_FIELD_ID, "test-blob-store");
    config.setBoolean(RESTORE_BLOBS, true);
    config.setBoolean(UNDELETE_BLOBS, true);
    config.setBoolean(INTEGRITY_CHECK, false);
    config.setBoolean(DRY_RUN, true);
    config.setInteger(SINCE_DAYS, 7);
    config.setString(TASK_NOTIFICATION_CONDITION, "FAILURE");
    return config;
  }

  /**
   * Helper method to create a restore task configuration.
   */
  private TaskConfiguration createRestoreTaskConfiguration() {
    TaskConfiguration config = new TaskConfiguration();
    config.setString(TASK_TYPE_ID, "restore-task");
    config.setString(BLOB_STORE_NAME_FIELD_ID, "test-restore-store");
    return config;
  }

  /**
   * Helper method to create a compact task configuration.
   */
  private TaskConfiguration createCompactTaskConfiguration() {
    TaskConfiguration config = new TaskConfiguration();
    config.setString(TASK_TYPE_ID, "compact-task");
    config.setString(BLOB_STORE_NAME_FIELD_ID, "test-compact-store");
    return config;
  }

  /**
   * Helper method to create an integrity check task configuration.
   */
  private TaskConfiguration createIntegrityCheckTaskConfiguration() {
    TaskConfiguration config = new TaskConfiguration();
    config.setString(TASK_TYPE_ID, "integrity-check-task");
    config.setString(BLOB_STORE_NAME_FIELD_ID, "test-integrity-store");
    return config;
  }
}