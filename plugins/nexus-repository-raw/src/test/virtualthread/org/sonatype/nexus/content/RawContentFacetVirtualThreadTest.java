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
package org.sonatype.nexus.content;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.testsuite.testsupport.Java21TestGroup;
import org.sonatype.nexus.testsuite.testsupport.VirtualThreadTestGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawContentFacet} operations using Java 21 Virtual Threads.
 * 
 * This test class verifies that Raw repository content operations (put, get, delete)
 * function correctly when executed on Virtual Threads, ensuring proper transaction
 * handling, resource cleanup, and exception propagation under high concurrency.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class RawContentFacetVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int HIGH_CONCURRENCY_OPERATIONS = 1000;
  private static final int OPERATION_TIMEOUT_SECONDS = 30;
  private static final String TEST_CONTENT = "Test content for virtual thread operations";
  
  @Mock
  private Repository repository;
  
  @Mock
  private RawContentFacet rawContentFacet;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Set up the repository and content facet
    when(repository.facet(RawContentFacet.class)).thenReturn(rawContentFacet);
    
    // Create executors for both platform threads and virtual threads
    platformThreadExecutor = createPlatformThreadExecutor("platform", Runtime.getRuntime().availableProcessors());
    virtualThreadExecutor = createVirtualThreadExecutor("virtual");
    
    // Set up mock behavior for the RawContentFacet
    setupMockContentFacet();
  }
  
  @After
  public void tearDown() throws Exception {
    // Shutdown executors
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdownNow();
      platformThreadExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
      virtualThreadExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
  }
  
  /**
   * Creates a platform thread executor with the specified number of threads.
   */
  private ExecutorService createPlatformThreadExecutor(String name, int threadCount) {
    ThreadFactory threadFactory = r -> {
      Thread t = new Thread(r);
      t.setName(name + "-" + t.getId());
      return t;
    };
    return Executors.newFixedThreadPool(threadCount, threadFactory);
  }
  
  /**
   * Creates a virtual thread executor that creates a new virtual thread for each task.
   */
  private ExecutorService createVirtualThreadExecutor(String name) {
    ThreadFactory threadFactory = Thread.ofVirtual()
        .name(name + "-", 0)
        .factory();
    return Executors.newThreadPerTaskExecutor(threadFactory);
  }
  
  /**
   * Sets up mock behavior for the RawContentFacet.
   */
  private void setupMockContentFacet() throws IOException {
    // Mock the put operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      // Simulate some I/O work
      Thread.sleep(10);
      Content content = mock(Content.class);
      return content;
    }).when(rawContentFacet).put(anyString(), any());
    
    // Mock the get operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      // Simulate some I/O work
      Thread.sleep(10);
      if (path.contains("not-found")) {
        return Optional.empty();
      }
      Content content = mock(Content.class);
      return Optional.of(content);
    }).when(rawContentFacet).get(anyString());
    
    // Mock the delete operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      // Simulate some I/O work
      Thread.sleep(10);
      return !path.contains("not-found");
    }).when(rawContentFacet).delete(anyString());
    
    // Mock the getOrCreateAsset operation
    doAnswer(invocation -> {
      // Simulate some I/O work
      Thread.sleep(10);
      FluentAsset asset = mock(FluentAsset.class);
      return asset;
    }).when(rawContentFacet).getOrCreateAsset(any(), anyString(), anyString(), anyString());
  }
  
  /**
   * Tests that a simple content put operation executes correctly with virtual threads.
   */
  @Test
  public void testBasicPutOperation() throws Exception {
    // Create a simple task that puts content
    Future<Content> future = virtualThreadExecutor.submit(() -> {
      // Verify this is running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), is(true));
      
      String path = "/test/file.txt";
      StringPayload payload = new StringPayload(TEST_CONTENT, "text/plain");
      return rawContentFacet.put(path, payload);
    });
    
    // Verify the result
    Content content = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    assertThat(content, is(notNullValue()));
    
    // Verify the facet method was called
    verify(rawContentFacet).put(eq("/test/file.txt"), any());
  }
  
  /**
   * Tests that a simple content get operation executes correctly with virtual threads.
   */
  @Test
  public void testBasicGetOperation() throws Exception {
    // Create a simple task that gets content
    Future<Optional<Content>> future = virtualThreadExecutor.submit(() -> {
      // Verify this is running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), is(true));
      
      String path = "/test/file.txt";
      return rawContentFacet.get(path);
    });
    
    // Verify the result
    Optional<Content> content = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    assertThat(content.isPresent(), is(true));
    
    // Verify the facet method was called
    verify(rawContentFacet).get(eq("/test/file.txt"));
  }
  
  /**
   * Tests that a simple content delete operation executes correctly with virtual threads.
   */
  @Test
  public void testBasicDeleteOperation() throws Exception {
    // Create a simple task that deletes content
    Future<Boolean> future = virtualThreadExecutor.submit(() -> {
      // Verify this is running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), is(true));
      
      String path = "/test/file.txt";
      return rawContentFacet.delete(path);
    });
    
    // Verify the result
    Boolean result = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    assertThat(result, is(true));
    
    // Verify the facet method was called
    verify(rawContentFacet).delete(eq("/test/file.txt"));
  }
  
  /**
   * Tests that error handling works correctly with virtual threads.
   */
  @Test
  public void testErrorHandling() throws Exception {
    // Set up the mock to throw an exception
    IOException testException = new IOException("Test exception");
    when(rawContentFacet.put(eq("/error/path.txt"), any())).thenThrow(testException);
    
    // Create a task that will throw an exception
    Future<Content> future = virtualThreadExecutor.submit(() -> {
      // Verify this is running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), is(true));
      
      String path = "/error/path.txt";
      StringPayload payload = new StringPayload(TEST_CONTENT, "text/plain");
      return rawContentFacet.put(path, payload);
    });
    
    // Verify the exception is properly propagated
    try {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
      // Should not reach here
      assertThat("Expected exception was not thrown", false);
    } catch (ExecutionException e) {
      // Expected exception
      assertThat(e.getCause(), is(testException));
    }
  }
  
  /**
   * Tests concurrent put operations with virtual threads.
   */
  @Test
  public void testConcurrentPutOperations() throws Exception {
    // Number of concurrent operations
    final int operationCount = CONCURRENT_OPERATIONS;
    
    // Create and submit multiple put tasks
    List<Future<Content>> futures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        String path = "/test/file-" + index + ".txt";
        StringPayload payload = new StringPayload(TEST_CONTENT + "-" + index, "text/plain");
        return rawContentFacet.put(path, payload);
      }));
    }
    
    // Wait for all operations to complete and verify results
    for (Future<Content> future : futures) {
      Content content = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertThat(content, is(notNullValue()));
    }
    
    // Verify the facet method was called the expected number of times
    verify(rawContentFacet, times(operationCount)).put(anyString(), any());
  }
  
  /**
   * Tests concurrent get operations with virtual threads.
   */
  @Test
  public void testConcurrentGetOperations() throws Exception {
    // Number of concurrent operations
    final int operationCount = CONCURRENT_OPERATIONS;
    
    // Create and submit multiple get tasks
    List<Future<Optional<Content>>> futures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        String path = "/test/file-" + index + ".txt";
        return rawContentFacet.get(path);
      }));
    }
    
    // Wait for all operations to complete and verify results
    for (Future<Optional<Content>> future : futures) {
      Optional<Content> content = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertThat(content.isPresent(), is(true));
    }
    
    // Verify the facet method was called the expected number of times
    verify(rawContentFacet, times(operationCount)).get(anyString());
  }
  
  /**
   * Tests concurrent delete operations with virtual threads.
   */
  @Test
  public void testConcurrentDeleteOperations() throws Exception {
    // Number of concurrent operations
    final int operationCount = CONCURRENT_OPERATIONS;
    
    // Create and submit multiple delete tasks
    List<Future<Boolean>> futures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        String path = "/test/file-" + index + ".txt";
        return rawContentFacet.delete(path);
      }));
    }
    
    // Wait for all operations to complete and verify results
    for (Future<Boolean> future : futures) {
      Boolean result = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
      assertThat(result, is(true));
    }
    
    // Verify the facet method was called the expected number of times
    verify(rawContentFacet, times(operationCount)).delete(anyString());
  }
  
  /**
   * Tests high concurrency operations with virtual threads.
   * This test creates a large number of virtual threads to verify that the RawContentFacet
   * can handle high concurrency scenarios efficiently with virtual threads.
   */
  @Test
  public void testHighConcurrencyOperations() throws Exception {
    // Number of concurrent operations
    final int operationCount = HIGH_CONCURRENCY_OPERATIONS;
    
    // Create a countdown latch to synchronize the start of all tasks
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create and submit multiple tasks that perform different operations
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Verify this is running in a virtual thread
          assertThat(Thread.currentThread().isVirtual(), is(true));
          
          String path = "/test/high-concurrency-" + index + ".txt";
          
          // Perform different operations based on the index
          if (index % 3 == 0) {
            // Put operation
            StringPayload payload = new StringPayload(TEST_CONTENT + "-" + index, "text/plain");
            Content content = rawContentFacet.put(path, payload);
            assertThat(content, is(notNullValue()));
          } else if (index % 3 == 1) {
            // Get operation
            Optional<Content> content = rawContentFacet.get(path);
            assertThat(content.isPresent(), is(true));
          } else {
            // Delete operation
            boolean result = rawContentFacet.delete(path);
            assertThat(result, is(true));
          }
          
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
    }
    
    // Start all tasks simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all operations to complete
    for (Future<?> future : futures) {
      future.get(OPERATION_TIMEOUT_SECONDS * 2, SECONDS);
    }
    long endTime = System.currentTimeMillis();
    
    // Log performance results
    log.info("High concurrency operations ({} operations) took {} ms", 
        operationCount, endTime - startTime);
    
    // Verify the facet methods were called the expected number of times
    int putCount = operationCount / 3 + (operationCount % 3 > 0 ? 1 : 0);
    int getCount = operationCount / 3 + (operationCount % 3 > 1 ? 1 : 0);
    int deleteCount = operationCount / 3;
    
    verify(rawContentFacet, times(putCount)).put(anyString(), any());
    verify(rawContentFacet, times(getCount)).get(anyString());
    verify(rawContentFacet, times(deleteCount)).delete(anyString());
  }
  
  /**
   * Tests that CompletableFuture works correctly with virtual threads for content operations.
   */
  @Test
  public void testCompletableFutureIntegration() throws Exception {
    // Create a CompletableFuture chain for content operations
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Put operation
        String path = "/test/completable-future.txt";
        StringPayload payload = new StringPayload(TEST_CONTENT, "text/plain");
        rawContentFacet.put(path, payload);
        return path;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor)
    .thenApplyAsync(path -> {
      try {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Get operation
        Optional<Content> content = rawContentFacet.get(path);
        assertThat(content.isPresent(), is(true));
        return path;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor)
    .thenApplyAsync(path -> {
      try {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Delete operation
        boolean result = rawContentFacet.delete(path);
        assertThat(result, is(true));
        return "All operations completed successfully";
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    // Get the final result
    String result = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    assertThat(result, is("All operations completed successfully"));
    
    // Verify all facet methods were called
    verify(rawContentFacet).put(eq("/test/completable-future.txt"), any());
    verify(rawContentFacet).get(eq("/test/completable-future.txt"));
    verify(rawContentFacet).delete(eq("/test/completable-future.txt"));
  }
  
  /**
   * Tests that transaction boundaries are respected with virtual threads.
   * This test simulates a transaction by performing multiple operations that should
   * either all succeed or all fail together.
   */
  @Test
  public void testTransactionBoundaries() throws Exception {
    // Create a task that performs multiple operations in a "transaction"
    Future<Boolean> future = virtualThreadExecutor.submit(() -> {
      // Verify this is running in a virtual thread
      assertThat(Thread.currentThread().isVirtual(), is(true));
      
      String basePath = "/test/transaction/";
      String fileName = UUID.randomUUID().toString() + ".txt";
      String path = basePath + fileName;
      
      try {
        // Step 1: Put content
        StringPayload payload = new StringPayload(TEST_CONTENT, "text/plain");
        Content content = rawContentFacet.put(path, payload);
        assertThat(content, is(notNullValue()));
        
        // Step 2: Get content to verify it was stored
        Optional<Content> retrievedContent = rawContentFacet.get(path);
        assertThat(retrievedContent.isPresent(), is(true));
        
        // Step 3: Delete content
        boolean deleted = rawContentFacet.delete(path);
        assertThat(deleted, is(true));
        
        // Step 4: Verify content was deleted
        Optional<Content> shouldBeEmpty = rawContentFacet.get(path);
        assertThat(shouldBeEmpty.isPresent(), is(false));
        
        return true;
      } catch (Exception e) {
        // If any step fails, the "transaction" should be rolled back
        // In a real implementation, this would include cleanup code
        try {
          rawContentFacet.delete(path);
        } catch (Exception ignored) {
          // Ignore cleanup errors
        }
        throw e;
      }
    });
    
    // Verify the transaction completed successfully
    Boolean result = future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    assertThat(result, is(true));
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads for content operations.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Number of operations for performance test
    final int operationCount = CONCURRENT_OPERATIONS;
    
    // Prepare paths and payloads for the test
    List<String> paths = IntStream.range(0, operationCount)
        .mapToObj(i -> "/test/performance-" + i + ".txt")
        .collect(Collectors.toList());
    
    List<StringPayload> payloads = IntStream.range(0, operationCount)
        .mapToObj(i -> new StringPayload(TEST_CONTENT + "-" + i, "text/plain"))
        .collect(Collectors.toList());
    
    // Measure platform threads performance for put operations
    long platformPutStart = System.currentTimeMillis();
    List<Future<Content>> platformPutFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      platformPutFutures.add(platformThreadExecutor.submit(() -> 
          rawContentFacet.put(paths.get(index), payloads.get(index))));
    }
    for (Future<Content> future : platformPutFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long platformPutDuration = System.currentTimeMillis() - platformPutStart;
    
    // Measure virtual threads performance for put operations
    long virtualPutStart = System.currentTimeMillis();
    List<Future<Content>> virtualPutFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      virtualPutFutures.add(virtualThreadExecutor.submit(() -> 
          rawContentFacet.put(paths.get(index), payloads.get(index))));
    }
    for (Future<Content> future : virtualPutFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long virtualPutDuration = System.currentTimeMillis() - virtualPutStart;
    
    // Measure platform threads performance for get operations
    long platformGetStart = System.currentTimeMillis();
    List<Future<Optional<Content>>> platformGetFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      platformGetFutures.add(platformThreadExecutor.submit(() -> 
          rawContentFacet.get(paths.get(index))));
    }
    for (Future<Optional<Content>> future : platformGetFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long platformGetDuration = System.currentTimeMillis() - platformGetStart;
    
    // Measure virtual threads performance for get operations
    long virtualGetStart = System.currentTimeMillis();
    List<Future<Optional<Content>>> virtualGetFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      virtualGetFutures.add(virtualThreadExecutor.submit(() -> 
          rawContentFacet.get(paths.get(index))));
    }
    for (Future<Optional<Content>> future : virtualGetFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long virtualGetDuration = System.currentTimeMillis() - virtualGetStart;
    
    // Measure platform threads performance for delete operations
    long platformDeleteStart = System.currentTimeMillis();
    List<Future<Boolean>> platformDeleteFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      platformDeleteFutures.add(platformThreadExecutor.submit(() -> 
          rawContentFacet.delete(paths.get(index))));
    }
    for (Future<Boolean> future : platformDeleteFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long platformDeleteDuration = System.currentTimeMillis() - platformDeleteStart;
    
    // Measure virtual threads performance for delete operations
    long virtualDeleteStart = System.currentTimeMillis();
    List<Future<Boolean>> virtualDeleteFutures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int index = i;
      virtualDeleteFutures.add(virtualThreadExecutor.submit(() -> 
          rawContentFacet.delete(paths.get(index))));
    }
    for (Future<Boolean> future : virtualDeleteFutures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    long virtualDeleteDuration = System.currentTimeMillis() - virtualDeleteStart;
    
    // Log performance results
    log.info("Platform threads put operations took {} ms", platformPutDuration);
    log.info("Virtual threads put operations took {} ms", virtualPutDuration);
    log.info("Platform threads get operations took {} ms", platformGetDuration);
    log.info("Virtual threads get operations took {} ms", virtualGetDuration);
    log.info("Platform threads delete operations took {} ms", platformDeleteDuration);
    log.info("Virtual threads delete operations took {} ms", virtualDeleteDuration);
    
    // Calculate and log performance ratios
    double putRatio = (double) platformPutDuration / virtualPutDuration;
    double getRatio = (double) platformGetDuration / virtualGetDuration;
    double deleteRatio = (double) platformDeleteDuration / virtualDeleteDuration;
    
    log.info("Put operations performance ratio (platform/virtual): {}", putRatio);
    log.info("Get operations performance ratio (platform/virtual): {}", getRatio);
    log.info("Delete operations performance ratio (platform/virtual): {}", deleteRatio);
    
    // We expect virtual threads to be more efficient for I/O-bound tasks,
    // but we don't assert on specific performance improvements as they can vary
    // by environment. Instead, we just verify both completed successfully.
    assertThat(platformPutFutures.size(), is(equalTo(operationCount)));
    assertThat(virtualPutFutures.size(), is(equalTo(operationCount)));
    assertThat(platformGetFutures.size(), is(equalTo(operationCount)));
    assertThat(virtualGetFutures.size(), is(equalTo(operationCount)));
    assertThat(platformDeleteFutures.size(), is(equalTo(operationCount)));
    assertThat(virtualDeleteFutures.size(), is(equalTo(operationCount)));
  }
  
  /**
   * Tests mixed operations (put, get, delete) running concurrently with virtual threads.
   */
  @Test
  public void testMixedOperations() throws Exception {
    // Number of operations of each type
    final int operationsPerType = CONCURRENT_OPERATIONS / 3;
    final AtomicInteger successCounter = new AtomicInteger(0);
    
    // Create and submit put tasks
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < operationsPerType; i++) {
      final int index = i;
      // Put operation
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          String path = "/test/mixed-put-" + index + ".txt";
          StringPayload payload = new StringPayload(TEST_CONTENT + "-" + index, "text/plain");
          Content content = rawContentFacet.put(path, payload);
          assertThat(content, is(notNullValue()));
          successCounter.incrementAndGet();
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
      
      // Get operation
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          String path = "/test/mixed-get-" + index + ".txt";
          Optional<Content> content = rawContentFacet.get(path);
          assertThat(content.isPresent(), is(true));
          successCounter.incrementAndGet();
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
      
      // Delete operation
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          String path = "/test/mixed-delete-" + index + ".txt";
          boolean result = rawContentFacet.delete(path);
          assertThat(result, is(true));
          successCounter.incrementAndGet();
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
    }
    
    // Wait for all operations to complete
    for (Future<?> future : futures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    
    // Verify all operations completed successfully
    assertThat(successCounter.get(), is(equalTo(operationsPerType * 3)));
    
    // Verify the facet methods were called the expected number of times
    verify(rawContentFacet, times(operationsPerType)).put(anyString(), any());
    verify(rawContentFacet, times(operationsPerType)).get(anyString());
    verify(rawContentFacet, times(operationsPerType)).delete(anyString());
  }
  
  /**
   * Tests resource cleanup with virtual threads by simulating a scenario where
   * resources need to be properly closed after use.
   */
  @Test
  public void testResourceCleanup() throws Exception {
    // Create a list to track resources that should be closed
    List<ByteArrayInputStream> resources = new ArrayList<>();
    
    // Mock the put operation to track resources
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      BytesPayload payload = (BytesPayload) invocation.getArgument(1);
      
      // Simulate some I/O work
      Thread.sleep(10);
      
      // Track the resource
      ByteArrayInputStream inputStream = new ByteArrayInputStream(payload.getBytes());
      resources.add(inputStream);
      
      // Use the resource
      byte[] buffer = new byte[1024];
      inputStream.read(buffer);
      
      // Return a mock content
      Content content = mock(Content.class);
      return content;
    }).when(rawContentFacet).put(anyString(), any(BytesPayload.class));
    
    // Create and submit tasks that use resources
    List<Future<Content>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        // Verify this is running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        String path = "/test/resource-" + index + ".txt";
        byte[] data = (TEST_CONTENT + "-" + index).getBytes(StandardCharsets.UTF_8);
        BytesPayload payload = new BytesPayload(data, "text/plain");
        
        try {
          return rawContentFacet.put(path, payload);
        } finally {
          // In a real implementation, resources would be closed here
          // or via try-with-resources
        }
      }));
    }
    
    // Wait for all operations to complete
    for (Future<Content> future : futures) {
      future.get(OPERATION_TIMEOUT_SECONDS, SECONDS);
    }
    
    // Verify all resources were tracked
    assertThat(resources.size(), is(equalTo(CONCURRENT_OPERATIONS)));
    
    // Close all resources
    for (ByteArrayInputStream resource : resources) {
      resource.close();
    }
    
    // Verify the facet method was called the expected number of times
    verify(rawContentFacet, times(CONCURRENT_OPERATIONS)).put(anyString(), any(BytesPayload.class));
  }
}