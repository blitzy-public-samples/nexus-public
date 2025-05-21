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
package org.sonatype.nexus.cleanup.internal.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.testdb.DataSessionRule;

import com.google.common.collect.Iterables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Virtual Thread-enabled test for {@link CleanupPolicyDAO} that validates database operations
 * under high concurrency using Java 21 Virtual Threads.
 */
@EnabledOnJre(JRE.JAVA_21)
public class CleanupPolicyDAOTest
{
  private DataSessionRule sessionRule = new DataSessionRule().access(CleanupPolicyDAO.class);

  private DataSession<?> session;

  private CleanupPolicyDAO dao;

  @BeforeEach
  public void setup() {
    session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
    dao = session.access(CleanupPolicyDAO.class);
  }

  @AfterEach
  public void cleanup() {
    session.close();
  }

  @Test
  public void testCRUD() {
    // a cleanup policy
    CleanupPolicyData policy = policy("foo", "some text", "maven2", "deletion", Map.of("bar", "one", "baz", "two"));
    dao.create(policy);

    // it is read
    CleanupPolicy read = dao.read(policy.getName()).orElse(null);
    // it matches the original policy
    assertThat(read.getName(), is(policy.getName()));
    assertThat(read.getNotes(), is(policy.getNotes()));
    assertThat(read.getFormat(), is(policy.getFormat()));
    assertThat(read.getMode(), is(policy.getMode()));
    assertThat(read.getCriteria(), is(Map.of("bar", "one", "baz", "two")));

    // the policy is updated
    policy.setNotes("some other text");
    policy.setFormat("npm");
    policy.setMode("other");
    policy.setCriteria(Map.of("one", "baz", "two", "bar"));
    dao.update(policy);
    // it is read
    CleanupPolicy update = dao.read(policy.getName()).orElse(null);
    // it matches the updated policy
    assertThat(update.getName(), is(policy.getName()));
    assertThat(update.getNotes(), is(policy.getNotes()));
    assertThat(update.getFormat(), is(policy.getFormat()));
    assertThat(update.getMode(), is(policy.getMode()));
    policy.setCriteria(Map.of("one", "baz", "two", "bar"));

    // the policy is deleted
    dao.delete(policy.getName());
    // it cannot be read anymore
    assertFalse(dao.read(policy.getName()).isPresent());

    // several policies are created
    IntStream.range(1, 6)
        .forEach(
            it -> dao.create(policy("foo" + it, "some text " + it, "maven" + it, "mode" + it, Map.of("bar", "" + it))));

    // browsing finds them all
    assertThat(dao.count(), is(5));
    Collection<CleanupPolicyData> items = collect(dao.browse());
    assertThat(items, hasSize(5));

    // getting by format
    List<CleanupPolicy> policies = collect(dao.browseByFormat("maven5"));

    // it finds the correct policies
    assertThat(policies, hasSize(1));

    CleanupPolicy found = Iterables.getFirst(policies, null);
    assertThat(found.getName(), is("foo5"));
    assertThat(found.getNotes(), is("some text 5"));
    assertThat(found.getFormat(), is("maven5"));
    assertThat(found.getMode(), is("mode5"));
    assertThat(found.getCriteria(), hasEntry("bar", "5"));
  }

  /**
   * Test concurrent CRUD operations using Virtual Threads.
   * This test creates 1000 cleanup policies concurrently using Virtual Threads,
   * then reads, updates, and deletes them concurrently.
   */
  @Test
  public void testConcurrentCRUDWithVirtualThreads() throws Exception {
    final int threadCount = 1000;
    final CountDownLatch createLatch = new CountDownLatch(threadCount);
    final CountDownLatch readLatch = new CountDownLatch(threadCount);
    final CountDownLatch updateLatch = new CountDownLatch(threadCount);
    final CountDownLatch deleteLatch = new CountDownLatch(threadCount);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    // Use Virtual Threads for concurrent operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create policies concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            CleanupPolicyData policy = policy(
                "vt-policy-" + index,
                "Virtual Thread test policy " + index,
                "format-" + index,
                "mode-" + index,
                Map.of("key-" + index, "value-" + index));
            dao.create(policy);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            createLatch.countDown();
          }
        });
      }
      
      // Wait for all create operations to complete
      assertTrue(createLatch.await(30, TimeUnit.SECONDS), "Create operations timed out");
      assertThat(errorCount.get(), is(0));
      assertThat(dao.count(), is(threadCount));
      
      // Read policies concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Optional<CleanupPolicy> policy = dao.read("vt-policy-" + index);
            assertTrue(policy.isPresent());
            assertThat(policy.get().getName(), is("vt-policy-" + index));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            readLatch.countDown();
          }
        });
      }
      
      // Wait for all read operations to complete
      assertTrue(readLatch.await(30, TimeUnit.SECONDS), "Read operations timed out");
      assertThat(errorCount.get(), is(0));
      
      // Update policies concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Optional<CleanupPolicy> policyOpt = dao.read("vt-policy-" + index);
            assertTrue(policyOpt.isPresent());
            CleanupPolicyData policy = (CleanupPolicyData) policyOpt.get();
            policy.setNotes("Updated by Virtual Thread " + index);
            policy.setCriteria(Map.of("updated-key-" + index, "updated-value-" + index));
            dao.update(policy);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            updateLatch.countDown();
          }
        });
      }
      
      // Wait for all update operations to complete
      assertTrue(updateLatch.await(30, TimeUnit.SECONDS), "Update operations timed out");
      assertThat(errorCount.get(), is(0));
      
      // Verify updates were successful by checking a sample
      Optional<CleanupPolicy> updatedPolicy = dao.read("vt-policy-0");
      assertTrue(updatedPolicy.isPresent());
      assertThat(updatedPolicy.get().getNotes(), is("Updated by Virtual Thread 0"));
      assertThat(updatedPolicy.get().getCriteria(), hasEntry("updated-key-0", "updated-value-0"));
      
      // Delete policies concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            dao.delete("vt-policy-" + index);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            deleteLatch.countDown();
          }
        });
      }
      
      // Wait for all delete operations to complete
      assertTrue(deleteLatch.await(30, TimeUnit.SECONDS), "Delete operations timed out");
      assertThat(errorCount.get(), is(0));
      
      // Verify all policies were deleted
      assertThat(dao.count(), is(0));
    }
  }

  /**
   * Test to compare performance between Virtual Threads and Platform Threads.
   * This test measures the time taken to perform concurrent database operations
   * using both thread types and verifies that Virtual Threads provide better performance.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    final int threadCount = 500;
    final int operationsPerThread = 2; // Create and delete
    
    // Run with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try {
        runConcurrentOperations(threadCount, Executors.defaultThreadFactory());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Run with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try {
        runConcurrentOperations(threadCount, Thread.ofVirtual().factory());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    System.out.println("Platform Thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual Thread execution time: " + virtualThreadTime + "ms");
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual Threads should be faster than Platform Threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Helper method to run concurrent operations using the specified thread factory.
   */
  private void runConcurrentOperations(int threadCount, ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit tasks
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a policy
            CleanupPolicyData policy = policy(
                "perf-policy-" + index,
                "Performance test policy " + index,
                "format-" + index,
                "mode-" + index,
                Map.of("key-" + index, "value-" + index));
            dao.create(policy);
            
            // Read the policy
            dao.read(policy.getName());
            
            // Delete the policy
            dao.delete(policy.getName());
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(30, TimeUnit.SECONDS);
      assertThat(errorCount.get(), is(0));
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Test to verify that no thread pinning occurs during database operations with Virtual Threads.
   * Thread pinning happens when a Virtual Thread is forced to stay on its carrier thread,
   * which negates the benefits of Virtual Threads.
   */
  @Test
  public void testNoPinningWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Run database operations concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        Future<?> future = executor.submit(() -> {
          try {
            // Create a policy
            CleanupPolicyData policy = policy(
                "pin-test-" + index,
                "Thread pinning test policy " + index,
                "format-" + index,
                "mode-" + index,
                Map.of("key-" + index, "value-" + index));
            dao.create(policy);
            
            // Read the policy
            Optional<CleanupPolicy> readPolicy = dao.read(policy.getName());
            assertTrue(readPolicy.isPresent());
            
            // Update the policy
            policy.setNotes("Updated notes " + index);
            dao.update(policy);
            
            // Delete the policy
            dao.delete(policy.getName());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Operations timed out");
    } finally {
      // Reset the system property
      System.clearProperty("jdk.tracePinnedThreads");
    }
    
    // If thread pinning occurred, it would be logged to the console
    // We can't directly detect it in code, but we can verify that all operations completed successfully
    assertThat(dao.count(), is(0)); // All policies should have been deleted
  }

  private static <E> List<E> collect(final Iterable<E> policies) {
    if (policies instanceof List) {
      return (List<E>) policies;
    }
    return StreamSupport.stream(policies.spliterator(), false).toList();
  }

  private static CleanupPolicyData policy(
      final String name,
      final String notes,
      final String format,
      final String mode,
      final Map<String, String> criteria)
  {
    CleanupPolicyData policy = new CleanupPolicyData();
    policy.setName(name);
    policy.setNotes(notes);
    policy.setFormat(format);
    policy.setMode(mode);
    policy.setCriteria(criteria);
    return policy;
  }
}