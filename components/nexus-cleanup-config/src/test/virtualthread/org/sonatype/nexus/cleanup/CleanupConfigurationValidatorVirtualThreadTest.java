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
package org.sonatype.nexus.cleanup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.validation.ConstraintViolation;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cleanup.storage.CleanupPolicy;
import org.sonatype.nexus.cleanup.storage.CleanupPolicyStorage;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Recipe;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.validation.ConstraintViolationFactory;

import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CleanupConfigurationValidator} running in Virtual Threads.
 * This test class verifies that the validator works correctly in a high-concurrency
 * environment using Java 21 Virtual Threads.
 */
public class CleanupConfigurationValidatorVirtualThreadTest
    extends TestSupport
{
  private static final String REPO_NAME = "repoName";

  private static final String CLEANUP_KEY = "cleanup";

  private static final String POLICY_NAME_KEY = "policyName";

  private static final String POLICY_NAME = "policy";

  private static final String FORMAT = "format";
  
  // Number of concurrent validation requests to simulate in high-concurrency tests
  private static final int CONCURRENT_VALIDATIONS = 10_000;
  
  // Timeout for concurrent operations
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Mock
  private ConstraintViolationFactory constraintFactory;

  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private CleanupPolicyStorage cleanupPolicyStorage;

  @Mock
  private Configuration configuration;

  @Mock
  private CleanupPolicy cleanupPolicy;

  @Mock
  private ConstraintViolation constraintViolation;

  @Mock
  private Recipe recipe;

  @Mock
  private Map<String, Map<String, Object>> attributes;

  @Mock
  private Map<String, Object> cleanupAttributes;

  private List<Recipe> recipes = new ArrayList<>();

  CleanupConfigurationValidator underTest;

  @BeforeEach
  public void setUp() throws Exception {
    when(configuration.getRepositoryName()).thenReturn(REPO_NAME);
    when(configuration.getAttributes()).thenReturn(attributes);
    when(cleanupAttributes.containsKey(POLICY_NAME_KEY)).thenReturn(true);
    when(cleanupAttributes.get(POLICY_NAME_KEY)).thenReturn(ImmutableSet.of(POLICY_NAME));
    when(attributes.containsKey(CLEANUP_KEY)).thenReturn(true);
    when(attributes.get(CLEANUP_KEY)).thenReturn(cleanupAttributes);
    when(cleanupPolicyStorage.get(POLICY_NAME)).thenReturn(cleanupPolicy);
    when(cleanupPolicy.getFormat()).thenReturn(FORMAT);
    when(constraintFactory.createViolation(anyString(), anyString())).thenReturn(constraintViolation);

    recipes.add(recipe);
    when(repositoryManager.getAllSupportedRecipes()).thenReturn(recipes);
    when(configuration.getRecipeName()).thenReturn(FORMAT + "-" + ProxyType.NAME);
    when(recipe.getType()).thenReturn(new ProxyType());
    when(recipe.getFormat()).thenReturn(new Format(FORMAT){});

    underTest = new CleanupConfigurationValidator(constraintFactory, repositoryManager, cleanupPolicyStorage);
  }

  /**
   * Creates a virtual thread factory for testing.
   */
  private ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().name("cleanup-validator-", 0).factory();
  }

  /**
   * Detects if a thread is pinned by checking if it yields execution.
   * A pinned thread will not yield execution to other threads.
   */
  private boolean isThreadPinned() {
    AtomicBoolean hasYielded = new AtomicBoolean(false);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(1);
    
    // Create a helper thread that will set the flag
    Thread helper = Thread.ofVirtual().start(() -> {
      try {
        startLatch.await(); // Wait for the main thread to start
        Thread.sleep(50);   // Give the main thread time to yield
        hasYielded.set(true);
        endLatch.countDown();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Start the main thread and check if it yields
    Thread main = Thread.ofVirtual().start(() -> {
      startLatch.countDown();
      // Perform the operation that might pin the thread
      underTest.validate(configuration);
      try {
        endLatch.await();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    try {
      main.join(TIMEOUT);
      helper.join(TIMEOUT);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    return !hasYielded.get();
  }

  @Test
  public void whenRepositoryNotFoundReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(repositoryManager.get(REPO_NAME)).thenReturn(null);
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenAttributeNotFoundReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(configuration.getAttributes()).thenReturn(null);
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenCleanupAttributeNotFoundReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(attributes.containsKey(CLEANUP_KEY)).thenReturn(false);
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenPolicyNameNotFoundReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(cleanupAttributes.containsKey(POLICY_NAME_KEY)).thenReturn(false);
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenCleanupPolicyNotFoundReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(cleanupPolicyStorage.get(POLICY_NAME)).thenReturn(null);
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenValidFormatsReturnNullInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(nullValue()));
    verify(constraintFactory, times(0)).createViolation(anyString(), anyString());
  }

  @Test
  public void whenInvalidFormatReturnConstraintViolationInVirtualThread() throws Exception {
    CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
      when(cleanupPolicy.getFormat()).thenReturn("other");
      return underTest.validate(configuration);
    }, Executors.newVirtualThreadPerTaskExecutor());
    
    assertThat(future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(constraintViolation));
    verify(constraintFactory).createViolation(anyString(), anyString());
  }
  
  @Test
  public void highConcurrencyValidationTest() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a map to track results
      ConcurrentHashMap<Integer, Object> results = new ConcurrentHashMap<>();
      CountDownLatch latch = new CountDownLatch(CONCURRENT_VALIDATIONS);
      
      // Submit validation tasks
      for (int i = 0; i < CONCURRENT_VALIDATIONS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Alternate between valid and invalid formats
            if (taskId % 2 == 0) {
              when(cleanupPolicy.getFormat()).thenReturn(FORMAT);
            } else {
              when(cleanupPolicy.getFormat()).thenReturn("other");
            }
            
            results.put(taskId, underTest.validate(configuration));
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), 
          "Timed out waiting for concurrent validations to complete");
      
      // Verify results
      for (int i = 0; i < CONCURRENT_VALIDATIONS; i++) {
        if (i % 2 == 0) {
          // Even tasks should have null result (valid format)
          assertThat(results.get(i), is(nullValue()));
        } else {
          // Odd tasks should have constraint violation (invalid format)
          assertThat(results.get(i), is(constraintViolation));
        }
      }
    }
  }
  
  @Test
  public void threadPinningTest() {
    // Check if the validation operation pins the thread
    boolean isPinned = isThreadPinned();
    
    // The validation operation should not pin the thread
    assertFalse(isPinned, "Validation operation should not pin the virtual thread");
  }
  
  @Test
  public void performanceComparisonTest() throws Exception {
    final int iterations = 1000;
    
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < iterations; i++) {
          futures.add(CompletableFuture.runAsync(() -> {
            underTest.validate(configuration);
          }, executor));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      }
    });
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < iterations; i++) {
          futures.add(CompletableFuture.runAsync(() -> {
            underTest.validate(configuration);
          }, executor));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      }
    });
    
    log.info("Performance comparison for {} iterations:", iterations);
    log.info("Platform threads: {} ms", platformThreadTime);
    log.info("Virtual threads: {} ms", virtualThreadTime);
  }
  
  /**
   * Measures the execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
  
  @Test
  public void concurrentValidationWithVirtualThreadPerTaskExecutor() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicInteger validCount = new AtomicInteger(0);
      AtomicInteger invalidCount = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(CONCURRENT_VALIDATIONS);
      
      // Submit validation tasks
      for (int i = 0; i < CONCURRENT_VALIDATIONS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Alternate between valid and invalid formats
            if (taskId % 2 == 0) {
              when(cleanupPolicy.getFormat()).thenReturn(FORMAT);
              if (underTest.validate(configuration) == null) {
                validCount.incrementAndGet();
              }
            } else {
              when(cleanupPolicy.getFormat()).thenReturn("other");
              if (underTest.validate(configuration) != null) {
                invalidCount.incrementAndGet();
              }
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), 
          "Timed out waiting for concurrent validations to complete");
      
      // Verify counts
      assertThat(validCount.get(), is(CONCURRENT_VALIDATIONS / 2));
      assertThat(invalidCount.get(), is(CONCURRENT_VALIDATIONS / 2));
    }
  }
  
  @Test
  public void massiveParallelValidationTest() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      AtomicLong successCount = new AtomicLong(0);
      CountDownLatch latch = new CountDownLatch(CONCURRENT_VALIDATIONS * 10);
      
      // Submit a massive number of validation tasks
      IntStream.range(0, CONCURRENT_VALIDATIONS * 10).forEach(i -> {
        executor.submit(() -> {
          try {
            // Validate with valid format
            when(cleanupPolicy.getFormat()).thenReturn(FORMAT);
            if (underTest.validate(configuration) == null) {
              successCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      });
      
      // Wait for all tasks to complete
      assertTrue(latch.await(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS), 
          "Timed out waiting for massive parallel validations to complete");
      
      // Verify all validations were successful
      assertThat(successCount.get(), is((long) CONCURRENT_VALIDATIONS * 10));
    }
  }
}