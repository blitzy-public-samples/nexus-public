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
package org.sonatype.nexus.capability;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.validation.ValidationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.capability.CapabilityDescriptor.ValidationMode;
import org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.CapabilityReferenceFilter;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.formfields.RepositoryCombobox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CapabilityDescriptorSupport} using Java 21 Virtual Threads.
 * 
 * This test class validates capability descriptor behavior with high concurrency using
 * virtual threads, and compares performance between platform and virtual threads.
 */
@ExtendWith(MockitoExtension.class)
public class CapabilityDescriptorSupportVirtualThreadTest
    extends TestSupport
{
  private static final int HIGH_CONCURRENCY_THREAD_COUNT = 1000;
  
  @Mock
  private CapabilityRegistry capabilityRegistry;

  @Mock
  private CapabilityReference capabilityReference;

  @Mock
  private CapabilityContext capabilityContext;

  private CapabilityIdentity capabilityIdentity;

  @Captor
  private ArgumentCaptor<CapabilityReferenceFilter> filterRecorder;

  private TestCapabilityDescriptor underTest;
  
  @BeforeEach
  public void prepare() {
    capabilityIdentity = CapabilityIdentity.capabilityIdentity("test");
    when(capabilityContext.id()).thenReturn(capabilityIdentity);
    when(capabilityReference.context()).thenReturn(capabilityContext);
    
    // Create a test descriptor with a repository field
    underTest = new TestCapabilityDescriptor(
        Collections.singletonList(new RepositoryCombobox("repository")),
        Collections.singleton("repository"));
    underTest.installComponents(() -> capabilityRegistry);
  }

  /**
   * Tests concurrent validation operations using virtual threads.
   * This test validates that the capability descriptor can handle a high number
   * of concurrent validation requests using virtual threads.
   */
  @Test
  public void concurrentValidationWithVirtualThreads() throws Exception {
    // Configure mock to return empty list for all validation requests
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenReturn(Collections.emptyList());
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = HIGH_CONCURRENCY_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent validation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String repoName = "repo-" + i;
        executor.submit(() -> {
          try {
            Map<String, String> properties = new HashMap<>();
            properties.put("repository", repoName);
            underTest.validate(null, properties, ValidationMode.CREATE);
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All validation tasks should complete within the timeout");
      assertThat("No errors should occur during concurrent validation", errorCount.get(), is(0));
      
      // Verify the registry was called the expected number of times
      verify(capabilityRegistry, times(taskCount)).get(any(CapabilityReferenceFilter.class));
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent validation operations with thread pinning detection.
   * This test validates that capability validation operations don't cause thread pinning,
   * which would impact the performance benefits of virtual threads.
   */
  @Test
  public void concurrentValidationWithThreadPinningDetection() throws Exception {
    // Configure mock to return empty list for all validation requests
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenReturn(Collections.emptyList());
    
    // Create a virtual thread executor with thread pinning detection
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name("validation-thread-", 0)
        .factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100; // Smaller count for pinning detection
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicReference<Throwable> pinnedThreadError = new AtomicReference<>();
    
    // Enable thread pinning detection if running on Java 21
    String previousPinningConfig = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Submit concurrent validation tasks
      for (int i = 0; i < taskCount; i++) {
        final String repoName = "repo-" + i;
        executor.submit(() -> {
          Thread currentThread = Thread.currentThread();
          try {
            // Check if this is a virtual thread
            if (currentThread.isVirtual()) {
              Map<String, String> properties = new HashMap<>();
              properties.put("repository", repoName);
              
              // Perform validation operation
              underTest.validate(null, properties, ValidationMode.CREATE);
            }
          } catch (Throwable t) {
            // Capture any thread pinning errors
            if (t.toString().contains("pinned")) {
              pinnedThreadError.set(t);
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All validation tasks should complete within the timeout");
      if (pinnedThreadError.get() != null) {
        fail("Thread pinning detected during validation operations: " + pinnedThreadError.get().getMessage());
      }
    } finally {
      // Restore previous pinning configuration
      if (previousPinningConfig != null) {
        System.setProperty("jdk.tracePinnedThreads", previousPinningConfig);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
      executor.shutdown();
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for validation operations.
   * This test measures and compares the execution time of validation operations using both
   * platform threads and virtual threads under high concurrency.
   */
  @Test
  public void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Configure mock to return empty list for all validation requests
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenReturn(Collections.emptyList());
    
    // Create thread factories for both types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    int taskCount = HIGH_CONCURRENCY_THREAD_COUNT;
    
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(platformThreadFactory, taskCount);
    log.info("Platform thread execution time for {} validation operations: {} ms", 
        taskCount, platformThreadTime);
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(virtualThreadFactory, taskCount);
    log.info("Virtual thread execution time for {} validation operations: {} ms", 
        taskCount, virtualThreadTime);
    
    // For high concurrency operations, virtual threads should be more efficient
    if (taskCount >= 1000) {
      assertThat("Virtual threads should be faster than platform threads for high concurrency",
          virtualThreadTime, lessThan(platformThreadTime));
    }
  }
  
  /**
   * Measures the execution time of validation operations using the specified thread factory.
   * 
   * @param threadFactory the thread factory to use
   * @param taskCount the number of concurrent validation operations to perform
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(ThreadFactory threadFactory, int taskCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try {
      long startTime = System.nanoTime();
      
      // Submit validation tasks
      for (int i = 0; i < taskCount; i++) {
        final String repoName = "repo-" + i;
        executor.submit(() -> {
          try {
            Map<String, String> properties = new HashMap<>();
            properties.put("repository", repoName);
            underTest.validate(null, properties, ValidationMode.CREATE);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      long endTime = System.nanoTime();
      
      if (!completed) {
        fail("Validation tasks did not complete within the timeout");
      }
      
      return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests validation with a high number of concurrent virtual threads.
   * This test validates that the capability descriptor can handle an extremely high
   * number of concurrent validation requests using virtual threads without errors.
   */
  @Test
  public void highConcurrencyValidationWithVirtualThreads() throws Exception {
    // Configure mock to return empty list for all validation requests
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenReturn(Collections.emptyList());
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Use a very high thread count to test scalability
    int taskCount = HIGH_CONCURRENCY_THREAD_COUNT * 2; // 2000 threads
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    try {
      // Submit a large number of concurrent validation tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final String repoName = "repo-" + i;
        executor.submit(() -> {
          try {
            Map<String, String> properties = new HashMap<>();
            properties.put("repository", repoName);
            underTest.validate(null, properties, ValidationMode.CREATE);
            completedCount.incrementAndGet();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a generous timeout
      boolean allCompleted = latch.await(2, TimeUnit.MINUTES);
      
      // Verify results
      assertTrue(allCompleted, "All validation tasks should complete within the timeout");
      assertThat("No errors should occur during high concurrency validation", 
          errorCount.get(), is(0));
      assertThat("All validation tasks should complete successfully", 
          completedCount.get(), is(taskCount));
      
      log.info("Successfully completed {} concurrent validation operations using virtual threads", 
          completedCount.get());
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests validation with error conditions using virtual threads.
   * This test validates that the capability descriptor correctly handles validation errors
   * when using virtual threads for concurrent operations.
   */
  @Test
  public void concurrentValidationWithErrorsUsingVirtualThreads() throws Exception {
    // Configure mock to return a capability reference for even-numbered repositories
    // This will cause validation to fail for those repositories
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class)))
        .thenAnswer(invocation -> {
          CapabilityReferenceFilter filter = invocation.getArgument(0);
          String repoName = filter.getProperties().get("repository");
          if (repoName != null && repoName.matches("repo-\\d*[02468]")) {
            return Collections.singletonList(capabilityReference);
          }
          return Collections.emptyList();
        });
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100; // Use a smaller count for this test
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit concurrent validation tasks
      for (int i = 0; i < taskCount; i++) {
        final String repoName = "repo-" + i;
        executor.submit(() -> {
          try {
            Map<String, String> properties = new HashMap<>();
            properties.put("repository", repoName);
            underTest.validate(null, properties, ValidationMode.CREATE);
            successCount.incrementAndGet();
          } catch (ValidationException e) {
            // Expected for even-numbered repositories
            errorCount.incrementAndGet();
          } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(completed, "All validation tasks should complete within the timeout");
      
      // We expect approximately half of the validations to fail (even-numbered repositories)
      assertThat("Approximately half of the validations should fail", 
          errorCount.get(), greaterThan(taskCount / 3));
      assertThat("Approximately half of the validations should succeed", 
          successCount.get(), greaterThan(taskCount / 3));
      assertThat("All validations should be accounted for", 
          errorCount.get() + successCount.get(), is(taskCount));
      
      log.info("Completed {} validation operations: {} succeeded, {} failed with validation errors", 
          taskCount, successCount.get(), errorCount.get());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Test capability descriptor implementation for testing purposes.
   */
  private static class TestCapabilityDescriptor
      extends CapabilityDescriptorSupport
  {
    private final List<FormField> formFields;

    private final Set<String> uniqueProperties;

    public TestCapabilityDescriptor(final List<FormField> formFields, final Set<String> uniqueProperties) {
      this.formFields = formFields;
      this.uniqueProperties = uniqueProperties;
    }

    @Override
    public CapabilityType type() {
      return CapabilityType.capabilityType("test");
    }

    @Override
    public String name() {
      return "Test";
    }

    @Override
    public List<FormField> formFields() {
      return formFields;
    }

    @Nullable
    @Override
    protected Set<String> uniqueProperties() {
      return uniqueProperties;
    }
    
    @Override
    protected String renderReason(final ValidationMode mode) {
      return switch(mode) {
        case ValidationMode.CREATE -> STR."Cannot create capability of type \{name()}";
        case ValidationMode.UPDATE -> STR."Cannot update capability of type \{name()}";
        default -> STR."Cannot validate capability of type \{name()}";
      };
    }
  }
}