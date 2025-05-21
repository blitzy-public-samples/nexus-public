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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.sonatype.nexus.cleanup.internal.storage.CleanupPolicyData;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CleanupPolicyData} using Java 21 pattern matching features.
 * 
 * @since 3.62
 */
@ExtendWith(MockitoExtension.class)
public class CleanupPolicyDataPatternMatchingTest
{
  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  private CleanupPolicyData testPolicy;

  private static final String TEST_NAME = "test-policy";
  private static final String TEST_FORMAT = "maven2";
  private static final String TEST_MODE = "delete";
  private static final String TEST_NOTES = "Test cleanup policy";
  private static final Map<String, String> TEST_CRITERIA = ImmutableMap.of(
      "regex", ".*-SNAPSHOT",
      "lastBlobUpdated", "30",
      "lastDownloaded", "60");

  @BeforeEach
  void setUp() {
    testPolicy = new CleanupPolicyData();
    testPolicy.setName(TEST_NAME);
    testPolicy.setFormat(TEST_FORMAT);
    testPolicy.setMode(TEST_MODE);
    testPolicy.setNotes(TEST_NOTES);
    testPolicy.setCriteria(TEST_CRITERIA);

    when(cleanupPolicyStorage.get(TEST_NAME)).thenReturn(testPolicy);
  }

  /**
   * Tests basic pattern matching with CleanupPolicyData to extract fields.
   */
  @Test
  void testBasicPatternMatching() {
    CleanupPolicy policy = cleanupPolicyStorage.get(TEST_NAME);
    
    // Using Java 21 pattern matching to extract fields
    if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
      assertThat(name, is(TEST_NAME));
      assertThat(format, is(TEST_FORMAT));
      assertThat(mode, is(TEST_MODE));
      assertThat(notes, is(TEST_NOTES));
      assertThat(criteria, is(TEST_CRITERIA));
    }
  }

  /**
   * Tests nested pattern matching with criteria map extraction.
   */
  @Test
  void testNestedPatternMatching() {
    CleanupPolicy policy = cleanupPolicyStorage.get(TEST_NAME);
    
    // Using nested pattern matching to extract and validate criteria
    if (policy instanceof CleanupPolicyData(var name, var notes, var format, var mode, var criteria)) {
      assertThat(criteria, hasEntry("regex", ".*-SNAPSHOT"));
      
      // Extract regex pattern using pattern matching
      if (criteria.get("regex") instanceof String regex && 
          regex.matches(".*-SNAPSHOT")) {
        assertThat("test-SNAPSHOT".matches(regex), is(true));
        assertThat("release".matches(regex), is(false));
      }
      
      // Extract lastBlobUpdated using pattern matching
      if (criteria.get("lastBlobUpdated") instanceof String lastBlobUpdated) {
        int days = Integer.parseInt(lastBlobUpdated);
        assertThat(days, is(30));
      }
    }
  }

  /**
   * Tests pattern matching with conditional logic for policy validation.
   */
  @Test
  void testPatternMatchingWithConditionalLogic() {
    CleanupPolicy policy = cleanupPolicyStorage.get(TEST_NAME);
    
    // Using pattern matching with conditional logic
    String result = switch (policy) {
      case CleanupPolicyData(var name, var notes, var format, "delete", var criteria) ->
          STR."Policy \{name} will delete components in \{format} format";
      case CleanupPolicyData(var name, var notes, var format, "retain", var criteria) ->
          STR."Policy \{name} will retain components in \{format} format";
      default -> "Unknown policy type";
    };
    
    assertThat(result, is(STR."Policy \{TEST_NAME} will delete components in \{TEST_FORMAT} format"));
  }

  /**
   * Tests pattern matching with record patterns for JDBC compatibility.
   * This simulates retrieving and processing data from a database.
   */
  @Test
  void testJdbcCompatibilityWithPatternMatching() {
    // Setup multiple policies to simulate database retrieval
    CleanupPolicyData policy1 = new CleanupPolicyData();
    policy1.setName("policy1");
    policy1.setFormat("maven2");
    policy1.setMode("delete");
    policy1.setCriteria(ImmutableMap.of("regex", ".*-SNAPSHOT"));
    
    CleanupPolicyData policy2 = new CleanupPolicyData();
    policy2.setName("policy2");
    policy2.setFormat("npm");
    policy2.setMode("retain");
    policy2.setCriteria(ImmutableMap.of("lastDownloaded", "90"));
    
    List<CleanupPolicy> policies = List.of(policy1, policy2);
    when(cleanupPolicyStorage.getAll()).thenReturn(policies);
    
    // Process policies using pattern matching
    int deleteCount = 0;
    int retainCount = 0;
    
    for (CleanupPolicy policy : cleanupPolicyStorage.getAll()) {
      switch (policy) {
        case CleanupPolicyData(var name, var notes, var format, "delete", var criteria) -> deleteCount++;
        case CleanupPolicyData(var name, var notes, var format, "retain", var criteria) -> retainCount++;
        default -> {}
      }
    }
    
    assertThat(deleteCount, is(1));
    assertThat(retainCount, is(1));
  }

  /**
   * Tests String Templates for error message formatting with pattern matching.
   */
  @Test
  void testStringTemplatesWithPatternMatching() {
    CleanupPolicy policy = cleanupPolicyStorage.get(TEST_NAME);
    
    String errorMessage = switch (policy) {
      case CleanupPolicyData(var name, var notes, var format, var mode, var criteria) 
          when !criteria.containsKey("regex") ->
          STR."Policy \{name} is missing required regex criteria";
      case CleanupPolicyData(var name, var notes, var format, var mode, var criteria) 
          when criteria.containsKey("regex") && criteria.get("regex").isEmpty() ->
          STR."Policy \{name} has empty regex criteria";
      case CleanupPolicyData(var name, var notes, var format, var mode, var criteria) ->
          STR."Policy \{name} has valid regex: \{criteria.get("regex")}";
      default -> "Invalid policy";
    };
    
    assertThat(errorMessage, is(STR."Policy \{TEST_NAME} has valid regex: \{TEST_CRITERIA.get("regex")}"));
  }

  /**
   * Tests Virtual Threads with pattern matching for concurrent policy processing.
   */
  @Test
  void testVirtualThreadsWithPatternMatching() throws Exception {
    // Setup multiple policies
    CleanupPolicyData policy1 = new CleanupPolicyData();
    policy1.setName("policy1");
    policy1.setFormat("maven2");
    policy1.setMode("delete");
    policy1.setCriteria(ImmutableMap.of("regex", ".*-SNAPSHOT"));
    
    CleanupPolicyData policy2 = new CleanupPolicyData();
    policy2.setName("policy2");
    policy2.setFormat("npm");
    policy2.setMode("retain");
    policy2.setCriteria(ImmutableMap.of("lastDownloaded", "90"));
    
    List<CleanupPolicy> policies = List.of(policy1, policy2);
    when(cleanupPolicyStorage.getAll()).thenReturn(policies);
    
    // Process policies concurrently using Virtual Threads
    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<String>> futures = cleanupPolicyStorage.getAll().stream()
          .map(policy -> CompletableFuture.supplyAsync(() -> {
            return switch (policy) {
              case CleanupPolicyData(var name, var notes, var format, "delete", var criteria) ->
                  STR."Processed delete policy: \{name}";
              case CleanupPolicyData(var name, var notes, var format, "retain", var criteria) ->
                  STR."Processed retain policy: \{name}";
              default -> "Unknown policy";
            };
          }, executor))
          .toList();
      
      // Wait for all futures to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0]));
      
      // Get results
      allFutures.join();
      List<String> results = futures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      assertThat(results.size(), is(2));
      assertThat(results.contains("Processed delete policy: policy1"), is(true));
      assertThat(results.contains("Processed retain policy: policy2"), is(true));
    }
  }
}