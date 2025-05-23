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
package org.sonatype.nexus.cleanup.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.sonatype.nexus.cleanup.internal.storage.CleanupPolicyData;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CleanupPolicyData} using Java 21 pattern matching features.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
class CleanupPolicyDataPatternMatchingTest
{
  private static final String TEST_NAME_1 = "test_policy_1";
  private static final String TEST_FORMAT_1 = "maven2";
  private static final String TEST_MODE_1 = "delete";
  private static final String TEST_NOTES_1 = "Test cleanup policy notes";
  
  private static final String TEST_NAME_2 = "test_policy_2";
  private static final String TEST_FORMAT_2 = "raw";
  private static final String TEST_MODE_2 = "clean";
  private static final String TEST_NOTES_2 = "Another test policy";
  
  private static final Map<String, String> TEST_CRITERIA_1 = ImmutableMap.of(
      "regex", ".*-SNAPSHOT.*",
      "lastDownloaded", "30",
      "lastBlobUpdated", "60");
  
  private static final Map<String, String> TEST_CRITERIA_2 = ImmutableMap.of(
      "regex", ".*\\.temp",
      "lastDownloaded", "15");

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  private List<CleanupPolicy> testPolicies;

  @BeforeEach
  void setUp() {
    testPolicies = new ArrayList<>();
    testPolicies.add(createCleanupPolicy(TEST_NAME_1, TEST_FORMAT_1, TEST_MODE_1, TEST_NOTES_1, TEST_CRITERIA_1));
    testPolicies.add(createCleanupPolicy(TEST_NAME_2, TEST_FORMAT_2, TEST_MODE_2, TEST_NOTES_2, TEST_CRITERIA_2));
    
    when(cleanupPolicyStorage.getAll()).thenReturn(testPolicies);
  }

  @AfterEach
  void tearDown() {
    testPolicies = null;
  }

  /**
   * Tests pattern matching with record patterns to extract and validate CleanupPolicyData fields.
   */
  @Test
  void testPatternMatchingForPolicyData() {
    List<CleanupPolicy> policies = cleanupPolicyStorage.getAll();
    assertThat(policies, notNullValue());
    assertThat(policies.size(), is(2));
    
    // Using pattern matching to extract and validate fields
    for (Object policy : policies) {
      if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
        // Validate based on extracted fields using pattern matching
        if (name.equals(TEST_NAME_1)) {
          assertAll(
              () -> assertEquals(TEST_FORMAT_1, format),
              () -> assertEquals(TEST_MODE_1, mode),
              () -> assertEquals(TEST_NOTES_1, notes),
              () -> assertEquals(TEST_CRITERIA_1, criteria)
          );
        } else if (name.equals(TEST_NAME_2)) {
          assertAll(
              () -> assertEquals(TEST_FORMAT_2, format),
              () -> assertEquals(TEST_MODE_2, mode),
              () -> assertEquals(TEST_NOTES_2, notes),
              () -> assertEquals(TEST_CRITERIA_2, criteria)
          );
        }
      }
    }
  }

  /**
   * Tests nested pattern matching for criteria map values.
   */
  @Test
  void testNestedPatternMatchingForCriteria() {
    List<CleanupPolicy> policies = cleanupPolicyStorage.getAll();
    
    for (Object policy : policies) {
      if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
        // Using nested pattern matching to extract regex criteria
        if (criteria instanceof Map<String, String> map && map.containsKey("regex")) {
          String regexPattern = map.get("regex");
          assertNotNull(regexPattern);
          
          // Validate regex pattern based on policy name
          if (name.equals(TEST_NAME_1)) {
            assertEquals(".*-SNAPSHOT.*", regexPattern);
            
            // Test the regex pattern against sample strings
            Pattern pattern = Pattern.compile(regexPattern);
            assertTrue(pattern.matcher("artifact-SNAPSHOT-1.0.jar").matches());
            assertFalse(pattern.matcher("artifact-1.0.jar").matches());
          } else if (name.equals(TEST_NAME_2)) {
            assertEquals(".*\\.temp", regexPattern);
            
            // Test the regex pattern against sample strings
            Pattern pattern = Pattern.compile(regexPattern);
            assertTrue(pattern.matcher("file.temp").matches());
            assertFalse(pattern.matcher("file.txt").matches());
          }
        }
      }
    }
  }

  /**
   * Tests String Template functionality for error messages.
   */
  @Test
  void testStringTemplatesForErrorMessages() {
    List<CleanupPolicy> policies = cleanupPolicyStorage.getAll();
    
    for (Object policy : policies) {
      if (policy instanceof CleanupPolicyData(var name, var format, var mode, var notes, var criteria)) {
        // Using String Templates for error message formatting
        String errorMessage = STR."Invalid cleanup policy configuration: \{name} for format \{format} with mode \{mode}";
        
        // Validate the formatted error message
        if (name.equals(TEST_NAME_1)) {
          assertEquals("Invalid cleanup policy configuration: test_policy_1 for format maven2 with mode delete", 
              errorMessage);
        } else if (name.equals(TEST_NAME_2)) {
          assertEquals("Invalid cleanup policy configuration: test_policy_2 for format raw with mode clean", 
              errorMessage);
        }
      }
    }
  }

  /**
   * Tests JDBC compatibility with Java 21 for cleanup policy database operations.
   * This test verifies that the JDBC drivers work correctly with Java 21 when handling cleanup policy data.
   */
  @Test
  void testJdbcCompatibilityWithJava21() throws SQLException {
    // Using H2 in-memory database for testing JDBC compatibility
    try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")) {
      // Create a test table for cleanup policies
      try (PreparedStatement stmt = conn.prepareStatement(
          "CREATE TABLE cleanup_policy (" +
          "  name VARCHAR(255) PRIMARY KEY, " +
          "  format VARCHAR(255), " +
          "  mode VARCHAR(50), " +
          "  notes VARCHAR(1000), " +
          "  regex VARCHAR(255), " +
          "  last_downloaded INT, " +
          "  last_blob_updated INT" +
          ")")) {
        stmt.execute();
      }
      
      // Insert test data
      for (CleanupPolicy policy : testPolicies) {
        if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
          try (PreparedStatement stmt = conn.prepareStatement(
              "INSERT INTO cleanup_policy (name, format, mode, notes, regex, last_downloaded, last_blob_updated) " +
              "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, name);
            stmt.setString(2, format);
            stmt.setString(3, mode);
            stmt.setString(4, notes);
            stmt.setString(5, criteria.getOrDefault("regex", null));
            stmt.setObject(6, criteria.containsKey("lastDownloaded") ? 
                Integer.parseInt(criteria.get("lastDownloaded")) : null);
            stmt.setObject(7, criteria.containsKey("lastBlobUpdated") ? 
                Integer.parseInt(criteria.get("lastBlobUpdated")) : null);
            stmt.executeUpdate();
          }
        }
      }
      
      // Query and verify data using pattern matching
      try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM cleanup_policy");
           ResultSet rs = stmt.executeQuery()) {
        
        List<Map<String, Object>> results = new ArrayList<>();
        while (rs.next()) {
          Map<String, Object> row = new HashMap<>();
          row.put("name", rs.getString("name"));
          row.put("format", rs.getString("format"));
          row.put("mode", rs.getString("mode"));
          row.put("notes", rs.getString("notes"));
          row.put("regex", rs.getString("regex"));
          row.put("lastDownloaded", rs.getObject("last_downloaded"));
          row.put("lastBlobUpdated", rs.getObject("last_blob_updated"));
          results.add(row);
        }
        
        assertThat(results.size(), is(2));
        
        // Verify results using pattern matching
        for (Map<String, Object> row : results) {
          if (row.get("name").equals(TEST_NAME_1)) {
            assertAll(
                () -> assertEquals(TEST_FORMAT_1, row.get("format")),
                () -> assertEquals(TEST_MODE_1, row.get("mode")),
                () -> assertEquals(TEST_NOTES_1, row.get("notes")),
                () -> assertEquals(TEST_CRITERIA_1.get("regex"), row.get("regex")),
                () -> assertEquals(Integer.parseInt(TEST_CRITERIA_1.get("lastDownloaded")), row.get("lastDownloaded")),
                () -> assertEquals(Integer.parseInt(TEST_CRITERIA_1.get("lastBlobUpdated")), row.get("lastBlobUpdated"))
            );
          } else if (row.get("name").equals(TEST_NAME_2)) {
            assertAll(
                () -> assertEquals(TEST_FORMAT_2, row.get("format")),
                () -> assertEquals(TEST_MODE_2, row.get("mode")),
                () -> assertEquals(TEST_NOTES_2, row.get("notes")),
                () -> assertEquals(TEST_CRITERIA_2.get("regex"), row.get("regex")),
                () -> assertEquals(Integer.parseInt(TEST_CRITERIA_2.get("lastDownloaded")), row.get("lastDownloaded")),
                () -> assertEquals(null, row.get("lastBlobUpdated"))
            );
          }
        }
      }
    }
  }

  /**
   * Tests Virtual Thread functionality for concurrent policy data processing operations.
   */
  @Test
  void testVirtualThreadsForConcurrentPolicyProcessing() throws InterruptedException {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int concurrentTasks = 100;
    CountDownLatch latch = new CountDownLatch(concurrentTasks);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent policy validation tasks using virtual threads
      for (int i = 0; i < concurrentTasks; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Get a random policy from the list
            CleanupPolicy policy = testPolicies.get(taskId % testPolicies.size());
            
            // Validate policy using pattern matching
            if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
              // Simulate some processing work
              Thread.sleep(10); // Small delay to simulate work
              
              // Validate regex pattern if present
              if (criteria.containsKey("regex")) {
                String regexPattern = criteria.get("regex");
                Pattern pattern = Pattern.compile(regexPattern);
                
                // Test pattern against a sample string
                String testString = name.equals(TEST_NAME_1) ? 
                    "artifact-SNAPSHOT-1.0.jar" : "file.temp";
                
                if (pattern.matcher(testString).matches()) {
                  successCount.incrementAndGet();
                }
              }
            }
          } 
          catch (Exception e) {
            // Log exception but don't fail the test - we'll check success count
            System.err.println(STR."Error in virtual thread task \{taskId}: \{e.getMessage()}");
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All concurrent tasks should complete", completed, is(true));
      assertThat("All policy validation operations should succeed", successCount.get(), is(concurrentTasks));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Helper method to create a CleanupPolicy instance for testing.
   */
  private CleanupPolicy createCleanupPolicy(
      final String name, final String format, final String mode, final String notes,
      final Map<String, String> criteria) 
  {
    CleanupPolicyData policyData = new CleanupPolicyData();
    policyData.setName(name);
    policyData.setFormat(format);
    policyData.setMode(mode);
    policyData.setNotes(notes);
    policyData.setCriteria(criteria);

    return policyData;
  }
}