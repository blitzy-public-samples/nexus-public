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
package org.sonatype.nexus.repository.maven.internal.search;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link MavenVersionNormalizer} using Java 21 Virtual Threads.
 * 
 * This test validates that version normalization functions correctly in a highly concurrent
 * environment by running multiple concurrent normalization operations using Virtual Threads.
 */
@VirtualThreadTestGroup
public class MavenVersionNormalizerVirtualThreadTest
{
  private final MavenVersionNormalizer underTest = new MavenVersionNormalizer();

  @Test
  public void testNullEmpty() {
    assertEquals("", underTest.getNormalizedVersion(null), "Null version should normalize to empty string");
    assertEquals("", underTest.getNormalizedVersion(""), "Empty version should normalize to empty string");
    assertEquals("", underTest.getNormalizedVersion(" "), "Blank version should normalize to empty string");
  }

  @Test
  public void testVersionExpansion()
  {
    assertVersionsEqual("1", "1.0");
    assertVersionsEqual("1", "1.0.0");
    assertVersionsEqual("1.0", "1.0.0");
  }

  @Test
  public void testAliases() {
    assertVersionsEqual("1ga", "1");
    assertVersionsEqual("1release", "1");
    assertVersionsEqual("1final", "1");
    assertVersionsEqual("1cr", "1rc");
    assertVersionsEqual("1a1", "1-alpha-1");
    assertVersionsEqual("1b2", "1-beta-2");
    assertVersionsEqual("1m3", "1-milestone-3");
  }

  @Test
  public void testCaseInsensitive() {
    assertVersionsEqual("1X", "1x");
    assertVersionsEqual("1A", "1a");
    assertVersionsEqual("1B", "1b");
    assertVersionsEqual("1M", "1m");
    assertVersionsEqual("1Ga", "1");
    assertVersionsEqual("1GA", "1");
    assertVersionsEqual("1RELEASE", "1");
    assertVersionsEqual("1release", "1");
    assertVersionsEqual("1RELeaSE", "1");
    assertVersionsEqual("1Final", "1");
    assertVersionsEqual("1FinaL", "1");
    assertVersionsEqual("1FINAL", "1");
    assertVersionsEqual("1Cr", "1Rc");
    assertVersionsEqual("1cR", "1rC");
    assertVersionsEqual("1m3", "1Milestone3");
    assertVersionsEqual("1m3", "1MileStone3");
    assertVersionsEqual("1m3", "1MILESTONE3");
  }

  @Test
  public void testNoSeparator() {
    assertVersionsEqual("1a", "1-a");
    assertVersionsEqual("1a", "1.0-a");
    assertVersionsEqual("1a", "1.0.0-a");
    assertVersionsEqual("1.0a", "1-a");
    assertVersionsEqual("1.0.0a", "1-a");
    assertVersionsEqual("1x", "1-x");
    assertVersionsEqual("1x", "1.0-x");
    assertVersionsEqual("1x", "1.0.0-x");
    assertVersionsEqual("1.0x", "1-x");
    assertVersionsEqual("1.0.0x", "1-x");
    // NEXUS-31494
    assertVersionsEqual("1.0-", "1");
  }

  @Test
  public void testOrder() {
    assertInOrder("1-alpha2", "1-alpha-123", "1-beta-2", "1-beta123", "1-m2", "1-m11", "1-rc", "1-cr2", "1-rc123",
        "1-sp2", "1-1", "1-2", "1-123");
    assertInOrder("2.0", "2.0.a", "2.0.2", "2.0.123", "2.1.0", "2.1-a", "2.1b", "2.1-c", "2.1-1", "2.2", "2.123");
    assertInOrder("11.a2", "11.a11", "11.b2", "11.b11", "11.m2", "11.m11", "11", "11.a", "11b", "11c", "11m");

    assertInOrder("1", "2");
    assertInOrder("1.5", "2");
    assertInOrder("1", "2.5");
    assertInOrder("1.0", "1.1");
    assertInOrder("1.1", "1.2");
    assertInOrder("1.0.0", "1.1");
    assertInOrder("1.0.1", "1.1");
    assertInOrder("1.1", "1.2.0");

    assertInOrder("1.0-alpha-1", "1.0");
    assertInOrder("1.0-alpha-1", "1.0-alpha-2");
    assertInOrder("1.0-alpha-1", "1.0-beta-1");

    assertInOrder("1.0", "1.0-1");
    assertInOrder("1.0-1", "1.0-2");
    assertInOrder("1.0.0", "1.0-1");

    assertInOrder("2.0-1", "2.0.1");
    assertInOrder("2.0.1-klm", "2.0.1-lmn");
    assertInOrder("2.0.1", "2.0.1-xyz");

    assertInOrder("2.0.1", "2.0.1-123");
    assertInOrder("2.0.1-xyz", "2.0.1-123");
  }

  @Test
  public void testSnapshotOrder() {
    assertInOrder("1-20211022.164208-1", "1");
    assertInOrder("1.0-beta-1", "1.0-20230122.854921-1");
    assertInOrder("2.1-20190801.333981-1", "2.1.0");
    assertInOrder("3-beta-1", "3.0.0-20100505.190345-1");
  }

  @Test
  public void testUnrecognizedVersionDefaultsToOriginalLogic() {
    assertEquals("asparagus.schoolbus", underTest.getNormalizedVersion("asparagus.schoolbus"), 
        "Unrecognized version should be normalized as-is");
    assertEquals("000000001.000000002.000000003.000000004.000000005", underTest.getNormalizedVersion("1.2.3.4.5"),
        "Numeric version should be normalized with leading zeros");
    assertEquals("develop-020211201.000171404-000000903", underTest.getNormalizedVersion("develop-20211201.171404-903"),
        "Snapshot version should be normalized with leading zeros");
    assertEquals("javaMaven-000000000.000000004.000000000-020230506.000135309-000000009", 
        underTest.getNormalizedVersion("javaMaven-0.4.0-20230506.135309-9"),
        "Complex version should be normalized correctly");
    assertEquals("000000001.000000001.000000049.b.develop-020230308.000153857-000000022",
            underTest.getNormalizedVersion("1.1.49.develop-20230308.153857-22"),
            "Version with multiple components should be normalized correctly");

    assertEquals("000000001.000000008.000000000.b.build_and_publish_docker_image-020230308.000153857-000000022",
            underTest.getNormalizedVersion("1.8.0-build_and_publish_docker_image-20230308.153857-22"),
            "Version with build info should be normalized correctly");

    assertEquals("000000001.000000031.000000000.b.build_000481184_special__request_000000002-020230619.000093516-000000004",
            underTest.getNormalizedVersion("1.31-build_481184_special__request_2-20230619.093516-4"),
            "Complex build version should be normalized correctly");

    assertInOrder("develop-20211130.182421-895", "develop-20211130.203249-896", "develop-20211201.111154-898",
        "develop-20211202.180605-904");
    assertInOrder("javaMaven-0.4.0-20230506.135309-8", "javaMaven-0.4.0-20230506.135309-9");

    assertInOrder("1.1.49.develop-20230308.153857-22", "1.1.49.develop-20230308.153857-23");
    assertInOrder("1.8.0-build_and_publish_docker_image-20230308.153857-1", "1.8.0-build_and_publish_docker_image-20230308.153857-2",
            "1.8.0-build_and_publish_docker_image-20230308.153857-10", "1.8.0-build_and_publish_docker_image-20230308.153857-11");

    assertInOrder("1.31-build_481184_special__request_2-20230619.093516-4", "1.31-build_481184_special__request_2-20230619.093516-5",
        "1.31-build_481184_special__request_2-20230619.093816-2", "1.31-build_481184_special__request_2-20230619.093916-1");
  }

  /**
   * Tests concurrent version normalization using Virtual Threads.
   * This test creates a large number of virtual threads, each normalizing a different version,
   * to validate that the normalizer works correctly under high concurrency.
   */
  @Test
  public void testConcurrentVersionNormalizationWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Number of concurrent normalization operations to perform
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicBoolean failed = new AtomicBoolean(false);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // List of versions to normalize concurrently
    List<String> versions = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      // Create a mix of different version types
      if (i % 5 == 0) {
        versions.add("1." + i);
      } else if (i % 5 == 1) {
        versions.add(i + "-SNAPSHOT");
      } else if (i % 5 == 2) {
        versions.add(i + "-alpha-" + (i % 10));
      } else if (i % 5 == 3) {
        versions.add("1." + i + ".0-20230619.09" + (i % 60) + "16-" + (i % 100));
      } else {
        versions.add("1." + i + "-beta-" + (i % 10));
      }
    }
    
    // Expected normalized versions
    List<String> expectedNormalizedVersions = new ArrayList<>();
    for (String version : versions) {
      expectedNormalizedVersions.add(underTest.getNormalizedVersion(version));
    }
    
    // Submit tasks to normalize versions concurrently
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          String version = versions.get(index);
          String normalizedVersion = underTest.getNormalizedVersion(version);
          String expectedNormalizedVersion = expectedNormalizedVersions.get(index);
          
          if (!normalizedVersion.equals(expectedNormalizedVersion)) {
            System.err.println("Version normalization failed for " + version + 
                ". Expected: " + expectedNormalizedVersion + ", Got: " + normalizedVersion);
            failed.set(true);
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          System.err.println("Exception during concurrent version normalization: " + e.getMessage());
          e.printStackTrace();
          failed.set(true);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete (with timeout)
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertTrue(completed, "All normalization tasks should complete within the timeout");
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent version normalization");
    assertTrue(!failed.get(), "All version normalizations should produce the expected results");
  }

  /**
   * Tests that version ordering is preserved when performed concurrently with Virtual Threads.
   * This test validates that the normalizer produces consistent ordering results even under high concurrency.
   */
  @Test
  public void testConcurrentVersionOrderingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that creates a new virtual thread for each task
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Define sets of versions that should maintain a specific order
    String[][] versionSets = {
        {"1.0", "1.1", "1.2", "1.3", "1.4"},
        {"1.0-alpha-1", "1.0-alpha-2", "1.0-beta-1", "1.0-rc1", "1.0"},
        {"1.0-SNAPSHOT", "1.0", "1.1-SNAPSHOT", "1.1"},
        {"1.0-20230101.123456-1", "1.0-20230101.123456-2", "1.0"},
        {"1.8.0-build_1-20230308.153857-1", "1.8.0-build_1-20230308.153857-2", "1.8.0-build_2-20230308.153857-1"}
    };
    
    int totalComparisons = 0;
    for (String[] versionSet : versionSets) {
      for (int i = 0; i < versionSet.length; i++) {
        for (int j = i + 1; j < versionSet.length; j++) {
          totalComparisons++;
        }
      }
    }
    
    CountDownLatch latch = new CountDownLatch(totalComparisons);
    AtomicBoolean failed = new AtomicBoolean(false);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit comparison tasks
    for (String[] versionSet : versionSets) {
      for (int i = 0; i < versionSet.length; i++) {
        for (int j = i + 1; j < versionSet.length; j++) {
          final String v1 = versionSet[i];
          final String v2 = versionSet[j];
          
          executor.submit(() -> {
            try {
              String n1 = underTest.getNormalizedVersion(v1);
              String n2 = underTest.getNormalizedVersion(v2);
              
              if (n1.compareTo(n2) >= 0) {
                System.err.println("Ordering violation: " + v1 + " (" + n1 + ") should be < " + 
                    v2 + " (" + n2 + ") but was " + n1.compareTo(n2));
                failed.set(true);
                errorCount.incrementAndGet();
              }
            } catch (Exception e) {
              System.err.println("Exception during concurrent version ordering: " + e.getMessage());
              e.printStackTrace();
              failed.set(true);
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
      }
    }
    
    // Wait for all tasks to complete (with timeout)
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify results
    assertTrue(completed, "All ordering tasks should complete within the timeout");
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent version ordering");
    assertTrue(!failed.get(), "All version orderings should be preserved under concurrency");
  }

  private void assertInOrder(String... versions) {
    for (int i = 1; i < versions.length; i++) {
      String v1 = versions[i - 1];
      String n1 = underTest.getNormalizedVersion(v1);
      for (int j = i; j < versions.length; j++) {
        String v2 = versions[j];
        String n2 = underTest.getNormalizedVersion(v2);
        assertTrue(n1.compareTo(n2) < 0, 
            String.format("Expected %s (%s) < %s (%s)", v1, n1, v2, n2));
        assertTrue(n2.compareTo(n1) > 0, 
            String.format("Expected %s (%s) > %s (%s)", v2, n2, v1, n1));
      }
    }
  }

  private void assertVersionsEqual(String v1, String v2) {
    assertEquals(underTest.getNormalizedVersion(v1), underTest.getNormalizedVersion(v2),
        String.format("Versions %s and %s should normalize to the same value", v1, v2));
  }
}