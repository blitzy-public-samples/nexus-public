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
package org.sonatype.nexus.repository.apt.internal.debian;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DebianVersion} class, updated for Java 21 features.
 *
 * @since 3.17
 * @updated Migrated to JUnit Jupiter and enhanced with Java 21 feature tests
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DebianVersion Tests")
public class DebianVersionTest
    extends TestSupport
{
  private static final String LOWER_EPOCH_UPSTREAM_DEBIAN = "2:7.3.429-2ubuntu2.1";

  private static final String HIGHER_EPOCH_UPSTREAM_DEBIAN = "3:7.3.429-2ubuntu2.1";

  private static final String UPSTREAM = "0.13";

  private static final String UPSTREAM_DEBIAN = "1.11-1";

  private static final String UPSTREAM_DEBIAN_TILDE = "30~pre9-5ubuntu2";

  @Test
  @DisplayName("Compare identical versions should return 0")
  @Tag("Java21TestGroup")
  public void testCompareVersionIsSimilar() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN)),
        is(0));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN).compareTo(new DebianVersion(UPSTREAM_DEBIAN)), is(0));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN_TILDE).compareTo(new DebianVersion(UPSTREAM_DEBIAN_TILDE)), is(0));
    assertThat(new DebianVersion(UPSTREAM).compareTo(new DebianVersion(UPSTREAM)), is(0));
  }

  @Test
  @DisplayName("Equality check for identical versions should return true")
  @Tag("Java21TestGroup")
  public void testEqualityVersionIsSimilar() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN),
        is(equalTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN))));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN), is(equalTo(new DebianVersion(UPSTREAM_DEBIAN))));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN_TILDE), is(equalTo(new DebianVersion(UPSTREAM_DEBIAN_TILDE))));
    assertThat(new DebianVersion(UPSTREAM), is(equalTo(new DebianVersion(UPSTREAM))));
  }

  @Test
  @DisplayName("Higher epoch version should be greater than lower epoch version")
  @Tag("Java21TestGroup")
  public void testCompareLowerEpochVersion() {
    assertThat(
        new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN)),
        is(1));
  }

  @Test
  @DisplayName("Lower epoch version should be less than higher epoch version")
  @Tag("Java21TestGroup")
  public void testCompareHigherEpochVersion() {
    assertThat(
        new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN)),
        is(-1));
  }

  @Test
  @DisplayName("Epoch value should be correctly parsed when present")
  @Tag("Java21TestGroup")
  public void testVerifyEpochVersionParthIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getEpoch(), is(equalTo(2)));
  }

  @Test
  @DisplayName("Epoch value should default to 0 when not present")
  @Tag("Java21TestGroup")
  public void testVerifyEpochVersionPartIsZero_IfEpochNotExist() {
    assertThat(new DebianVersion(UPSTREAM).getEpoch(), is(equalTo(0)));
  }

  @Test
  @DisplayName("Upstream version should be correctly parsed when all parts are present")
  @Tag("Java21TestGroup")
  public void testVerifyUpstreamVersionPartIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getUpstreamVersion(), is(equalTo("7.3.429")));
  }

  @Test
  @DisplayName("Upstream version should be correctly parsed when epoch and debian revision are missing")
  @Tag("Java21TestGroup")
  public void testVerifyUpstreamVersionPartIsCorrect_IfThereAreNoEpochAndDebian() {
    assertThat(new DebianVersion(UPSTREAM).getUpstreamVersion(), is(equalTo("0.13")));
  }

  @Test
  @DisplayName("Debian revision should be correctly parsed when present")
  @Tag("Java21TestGroup")
  public void testVerifyDebianVersionPartIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getDebianRevision(), is(equalTo("2ubuntu2.1")));
  }

  @Test
  @DisplayName("Test Java 21 record pattern matching in equals method")
  @Tag("Java21TestGroup")
  public void testRecordPatternMatching() {
    // Create two identical DebianVersion objects
    DebianVersion version1 = new DebianVersion(UPSTREAM_DEBIAN);
    DebianVersion version2 = new DebianVersion(UPSTREAM_DEBIAN);
    Object nonVersionObject = "not a version";
    
    // Test equals method which uses pattern matching: if (obj instanceof DebianVersion other)
    assertTrue(version1.equals(version2), "Equal DebianVersion objects should return true from equals");
    assertTrue(!version1.equals(nonVersionObject), "DebianVersion compared to non-version object should return false");
    
    // Verify hashCode consistency with equals
    assertEquals(version1.hashCode(), version2.hashCode(), "Equal objects should have equal hash codes");
  }

  @Test
  @DisplayName("Test Java 21 string templates in toString method")
  @Tag("Java21TestGroup")
  public void testStringTemplates() {
    // Test toString method which uses string templates
    DebianVersion versionWithEpoch = new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN);
    DebianVersion versionWithoutEpoch = new DebianVersion(UPSTREAM_DEBIAN);
    DebianVersion versionWithoutDebianRevision = new DebianVersion(UPSTREAM);
    
    // Verify string templates work correctly in toString
    assertEquals("2:7.3.429-2ubuntu2.1", versionWithEpoch.toString(), 
        "Version with epoch should format correctly with string templates");
    assertEquals("1.11-1", versionWithoutEpoch.toString(), 
        "Version without epoch should format correctly with string templates");
    assertEquals("0.13", versionWithoutDebianRevision.toString(), 
        "Version without debian revision should format correctly with string templates");
  }

  @Test
  @DisplayName("Test Java 21 switch expressions in version comparison")
  @Tag("Java21TestGroup")
  public void testSwitchExpressions() {
    // Create versions that will exercise the switch expressions in compareNumeric and priorityClass
    DebianVersion v1 = new DebianVersion("1.0-1");
    DebianVersion v2 = new DebianVersion("1.0-2");
    DebianVersion v3 = new DebianVersion("1.0~rc1"); // Uses tilde which has special priority
    
    // These comparisons will exercise the switch expressions
    assertTrue(v1.compareTo(v2) < 0, "1.0-1 should be less than 1.0-2");
    assertTrue(v3.compareTo(v1) < 0, "1.0~rc1 should be less than 1.0-1 due to tilde priority");
    
    // Test with empty numeric parts to exercise the switch expression for empty strings
    DebianVersion vWithEmptyNumeric = new DebianVersion("1.a");
    DebianVersion vWithNonEmptyNumeric = new DebianVersion("1.a1");
    assertTrue(vWithEmptyNumeric.compareTo(vWithNonEmptyNumeric) < 0, 
        "Version with empty numeric part should be less than version with non-empty numeric part");
  }

  @Test
  @DisplayName("Test concurrent version comparison with Virtual Threads")
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  public void testConcurrentVersionComparisonWithVirtualThreads() throws Exception {
    // Create a list of version pairs to compare
    List<DebianVersion[]> versionPairs = new ArrayList<>();
    versionPairs.add(new DebianVersion[] {new DebianVersion(UPSTREAM), new DebianVersion(UPSTREAM)});
    versionPairs.add(new DebianVersion[] {new DebianVersion(UPSTREAM_DEBIAN), new DebianVersion(UPSTREAM_DEBIAN)});
    versionPairs.add(new DebianVersion[] {new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN), 
                                         new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN)});
    versionPairs.add(new DebianVersion[] {new DebianVersion(UPSTREAM_DEBIAN_TILDE), 
                                         new DebianVersion(UPSTREAM_DEBIAN)});
    
    // Expected comparison results for each pair
    int[] expectedResults = {0, 0, -1, -1};
    
    // Counter for completed tasks
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Use Java 21 Virtual Threads via Executors.newVirtualThreadPerTaskExecutor()
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> results = new ArrayList<>();
      
      // Submit comparison tasks to the virtual thread executor
      for (int i = 0; i < versionPairs.size(); i++) {
        final int index = i;
        results.add(executor.submit(() -> {
          // Get the thread name to verify it's a virtual thread
          String threadName = Thread.currentThread().toString();
          logger.info("Running comparison on thread: {}", threadName);
          assertTrue(threadName.contains("VirtualThread"), "Should be running on a virtual thread");
          
          // Perform the version comparison
          DebianVersion v1 = versionPairs.get(index)[0];
          DebianVersion v2 = versionPairs.get(index)[1];
          int result = v1.compareTo(v2);
          
          // Increment completed task counter
          completedTasks.incrementAndGet();
          
          return result;
        }));
      }
      
      // Verify all results match expected values
      for (int i = 0; i < results.size(); i++) {
        assertEquals(expectedResults[i], results.get(i).get(), 
            "Comparison result should match expected value for pair " + i);
      }
      
      // Verify all tasks completed
      assertEquals(versionPairs.size(), completedTasks.get(), "All tasks should have completed");
    }
  }

  @Test
  @DisplayName("Test version parsing with individual Virtual Threads")
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  public void testVersionParsingWithIndividualVirtualThreads() throws Exception {
    // Create a list of version strings to parse
    List<String> versionStrings = List.of(
        UPSTREAM, UPSTREAM_DEBIAN, LOWER_EPOCH_UPSTREAM_DEBIAN, HIGHER_EPOCH_UPSTREAM_DEBIAN, UPSTREAM_DEBIAN_TILDE
    );
    
    List<CompletableFuture<DebianVersion>> futures = new ArrayList<>();
    
    // Parse each version string in a separate virtual thread
    for (String versionString : versionStrings) {
      CompletableFuture<DebianVersion> future = CompletableFuture.supplyAsync(() -> {
        // Get the thread name to verify it's a virtual thread
        Thread thread = Thread.currentThread();
        logger.info("Parsing version on thread: {}", thread);
        
        // Create a virtual thread for parsing if not already on one
        if (!thread.toString().contains("VirtualThread")) {
          try {
            return Thread.ofVirtual().name("version-parser-" + versionString).start(() -> {
              logger.info("Parsing in nested virtual thread: {}", Thread.currentThread());
              return new DebianVersion(versionString);
            }).join();
          }
          catch (Exception e) {
            throw new RuntimeException("Failed to create virtual thread", e);
          }
        }
        
        // Parse the version string
        return new DebianVersion(versionString);
      });
      
      futures.add(future);
    }
    
    // Wait for all parsing operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
    
    // Wait with timeout to ensure test doesn't hang
    allFutures.get(5, TimeUnit.SECONDS);
    
    // Verify all versions were parsed correctly
    for (int i = 0; i < versionStrings.size(); i++) {
      DebianVersion version = futures.get(i).get();
      assertNotNull(version, "Parsed version should not be null");
      assertEquals(versionStrings.get(i), version.toString(), 
          "Parsed version should match original string after toString");
    }
  }

  @Test
  @DisplayName("Test exception handling with null version in Virtual Thread")
  @Tag("Java21TestGroup")
  @Tag("VirtualThreadTestGroup")
  public void testExceptionHandlingWithNullVersionInVirtualThread() {
    // Create and start a virtual thread to test exception handling
    Thread virtualThread = Thread.ofVirtual()
        .name("exception-test-thread")
        .start(() -> {
          // Verify we're running on a virtual thread
          String threadName = Thread.currentThread().toString();
          logger.info("Running exception test on thread: {}", threadName);
          assertThat(threadName, startsWith("VirtualThread"));
          
          // Test that NullPointerException is thrown when version is null
          assertThrows(NullPointerException.class, () -> new DebianVersion(null),
              "DebianVersion constructor should throw NullPointerException for null input");
        });
    
    // Wait for the virtual thread to complete
    try {
      virtualThread.join(5000); // 5 second timeout
      assertThat(virtualThread.isAlive(), is(false));
    }
    catch (InterruptedException e) {
      throw new RuntimeException("Virtual thread was interrupted", e);
    }
  }
}