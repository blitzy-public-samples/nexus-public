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

package org.sonatype.nexus.repository.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.inject.Provider;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.group.BlobStoreGroup;
import org.sonatype.nexus.repository.internal.blobstore.BlobStoreStateHealthCheck;

import com.codahale.metrics.health.HealthCheck.Result;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStoreStateHealthCheck} using Java 21 Virtual Threads.
 * 
 * This test suite verifies that health check operations work correctly under the new threading model,
 * particularly when checking multiple blob stores concurrently.
 */
public class BlobStoreStateHealthCheckVirtualThreadTest
    extends TestSupport
{
  @Mock
  private BlobStoreManager blobStoreManager;

  private final Provider<BlobStoreManager> blobStoreManagerProvider = () -> blobStoreManager;

  private final BlobStoreStateHealthCheck healthCheck = new BlobStoreStateHealthCheck(blobStoreManagerProvider);

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Before
  public void setUp() throws Exception {
    when(blobStoreManager.browse()).thenReturn(Collections.singletonList(blobStore));
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn("blob-store-name");
  }

  /**
   * Verifies that health checks can be performed concurrently using virtual threads.
   */
  @Test
  public void concurrentHealthChecksWithVirtualThreads() throws Exception {
    // Setup multiple blob stores with different states
    List<BlobStore> blobStores = setupMultipleBlobStores(10);
    when(blobStoreManager.browse()).thenReturn(blobStores);

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Run health checks concurrently
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(10);
      ConcurrentMap<Integer, Result> results = new ConcurrentHashMap<>();

      for (int i = 0; i < 10; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            results.put(index, healthCheck.check());
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      startLatch.countDown(); // Start all threads at once
      completionLatch.await(5, TimeUnit.SECONDS); // Wait for all threads to complete

      // Verify all results are the same (health checks are thread-safe)
      Result firstResult = results.get(0);
      for (int i = 1; i < 10; i++) {
        assertThat(results.get(i).isHealthy(), is(firstResult.isHealthy()));
        if (!firstResult.isHealthy()) {
          assertThat(results.get(i).getMessage(), is(firstResult.getMessage()));
        }
      }

      // Verify the health check result is correct
      assertFalse(firstResult.isHealthy());
      assertThat(firstResult.getMessage(), containsString("5/10 blob stores report issues"));
    }
  }

  /**
   * Tests health check behavior with a large number of blob stores (100+) to verify scalability with virtual threads.
   */
  @Test
  public void largeNumberOfBlobStoresWithVirtualThreads() throws Exception {
    // Setup a large number of blob stores
    int blobStoreCount = 100;
    List<BlobStore> blobStores = setupMultipleBlobStores(blobStoreCount);
    when(blobStoreManager.browse()).thenReturn(blobStores);

    // Run health check with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> healthCheck.check(), executor);
      Result result = future.get(5, TimeUnit.SECONDS);

      // Verify the result
      assertFalse(result.isHealthy());
      assertThat(result.getMessage(), containsString(blobStoreCount / 2 + "/" + blobStoreCount + " blob stores report issues"));
    }
  }

  /**
   * Compares performance between virtual threads and platform threads for health checks.
   */
  @Test
  public void performanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Setup a large number of blob stores to make the performance difference noticeable
    int blobStoreCount = 200;
    List<BlobStore> blobStores = setupMultipleBlobStores(blobStoreCount);
    when(blobStoreManager.browse()).thenReturn(blobStores);

    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
        List<CompletableFuture<Result>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
          futures.add(CompletableFuture.supplyAsync(() -> healthCheck.check(), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
      }
    });

    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<CompletableFuture<Result>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
          futures.add(CompletableFuture.supplyAsync(() -> healthCheck.check(), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
      }
    });

    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // We don't assert on specific times as they can vary by environment,
    // but we log them for informational purposes
  }

  /**
   * Tests that health checks can be performed simultaneously across many blob stores without thread pinning issues.
   */
  @Test
  public void concurrentHealthChecksWithManyVirtualThreads() throws Exception {
    // Setup a moderate number of blob stores
    int blobStoreCount = 50;
    List<BlobStore> blobStores = setupMultipleBlobStores(blobStoreCount);
    when(blobStoreManager.browse()).thenReturn(blobStores);

    // Create a large number of virtual threads to simulate high concurrency
    int threadCount = 1000;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);

      // Submit many concurrent health check tasks
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            Result result = healthCheck.check();
            if (!result.isHealthy() && 
                result.getMessage().contains(blobStoreCount / 2 + "/" + blobStoreCount + " blob stores report issues")) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      startLatch.countDown(); // Start all threads at once
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);

      // Verify all threads completed and produced correct results
      assertTrue("Not all threads completed in time", completed);
      assertThat(successCount.get(), is(threadCount));
    }
  }

  /**
   * Tests that health check results are correctly aggregated when run with virtual threads.
   */
  @Test
  public void healthCheckResultAggregationWithVirtualThreads() throws Exception {
    // Setup blob stores with various issues
    List<BlobStore> blobStores = new ArrayList<>();
    
    // Add 3 healthy blob stores
    for (int i = 0; i < 3; i++) {
      BlobStore store = mock(BlobStore.class);
      BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
      when(store.getBlobStoreConfiguration()).thenReturn(config);
      when(config.getName()).thenReturn("healthy-store-" + i);
      when(config.getType()).thenReturn(FileBlobStore.TYPE);
      when(store.isStarted()).thenReturn(true);
      when(store.isWritable()).thenReturn(true);
      when(store.isStorageAvailable()).thenReturn(true);
      blobStores.add(store);
    }
    
    // Add 2 not started blob stores
    for (int i = 0; i < 2; i++) {
      BlobStore store = mock(BlobStore.class);
      BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
      when(store.getBlobStoreConfiguration()).thenReturn(config);
      when(config.getName()).thenReturn("not-started-store-" + i);
      when(config.getType()).thenReturn(FileBlobStore.TYPE);
      when(store.isStarted()).thenReturn(false);
      when(store.isWritable()).thenReturn(true);
      when(store.isStorageAvailable()).thenReturn(true);
      blobStores.add(store);
    }
    
    // Add 2 not writable blob stores
    for (int i = 0; i < 2; i++) {
      BlobStore store = mock(BlobStore.class);
      BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
      when(store.getBlobStoreConfiguration()).thenReturn(config);
      when(config.getName()).thenReturn("not-writable-store-" + i);
      when(config.getType()).thenReturn(FileBlobStore.TYPE);
      when(store.isStarted()).thenReturn(true);
      when(store.isWritable()).thenReturn(false);
      when(store.isStorageAvailable()).thenReturn(true);
      blobStores.add(store);
    }
    
    // Add 1 not available blob store
    BlobStore unavailableStore = mock(BlobStore.class);
    BlobStoreConfiguration unavailableConfig = mock(BlobStoreConfiguration.class);
    when(unavailableStore.getBlobStoreConfiguration()).thenReturn(unavailableConfig);
    when(unavailableConfig.getName()).thenReturn("not-available-store");
    when(unavailableConfig.getType()).thenReturn(FileBlobStore.TYPE);
    when(unavailableStore.isStarted()).thenReturn(true);
    when(unavailableStore.isWritable()).thenReturn(true);
    when(unavailableStore.isStorageAvailable()).thenReturn(false);
    blobStores.add(unavailableStore);
    
    when(blobStoreManager.browse()).thenReturn(blobStores);

    // Run health check with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> healthCheck.check(), executor);
      Result result = future.get(5, TimeUnit.SECONDS);

      // Verify the result contains all issues
      assertFalse(result.isHealthy());
      String message = result.getMessage();
      assertThat(message, containsString("5/8 blob stores report issues"));
      assertThat(message, containsString("not-started-store-0"));
      assertThat(message, containsString("not-started-store-1"));
      assertThat(message, containsString("not-writable-store-0"));
      assertThat(message, containsString("not-writable-store-1"));
      assertThat(message, containsString("not-available-store"));
    }
  }

  /**
   * Helper method to set up multiple blob stores with alternating health states.
   */
  private List<BlobStore> setupMultipleBlobStores(int count) {
    List<BlobStore> blobStores = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      BlobStore store = mock(BlobStore.class);
      BlobStoreConfiguration config = mock(BlobStoreConfiguration.class);
      
      when(store.getBlobStoreConfiguration()).thenReturn(config);
      when(config.getName()).thenReturn("blob-store-" + i);
      when(config.getType()).thenReturn(FileBlobStore.TYPE);
      
      // Make half the blob stores unhealthy with various issues
      if (i % 2 == 0) {
        when(store.isStarted()).thenReturn(true);
        when(store.isWritable()).thenReturn(true);
        when(store.isStorageAvailable()).thenReturn(true);
      } 
      else {
        // Distribute different issues across the unhealthy stores
        if (i % 6 == 1) {
          when(store.isStarted()).thenReturn(false);
          when(store.isWritable()).thenReturn(true);
          when(store.isStorageAvailable()).thenReturn(true);
        } 
        else if (i % 6 == 3) {
          when(store.isStarted()).thenReturn(true);
          when(store.isWritable()).thenReturn(false);
          when(store.isStorageAvailable()).thenReturn(true);
        } 
        else {
          when(store.isStarted()).thenReturn(true);
          when(store.isWritable()).thenReturn(true);
          when(store.isStorageAvailable()).thenReturn(false);
        }
      }
      
      blobStores.add(store);
    }
    return blobStores;
  }

  /**
   * Helper method to measure execution time of a task.
   */
  private long measurePerformance(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}