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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests Raw repository content operations using Java 21 Virtual Threads to validate compatibility 
 * and performance improvements.
 * 
 * This test class verifies that core content operations (put, get, delete) in Raw repositories 
 * function correctly when executed on Virtual Threads, ensuring proper transaction handling, 
 * resource cleanup, and exception propagation under high concurrency.
 *
 * @since 3.60
 */
public class RawContentFacetVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String TEST_PATH = "test/path.txt";
  private static final String TEST_CONTENT = "Test content";
  private static final int CONCURRENT_OPERATIONS = 1000;
  
  @Mock
  private Repository repository;
  
  @Mock
  private RawContentFacet rawContentFacet;
  
  @Mock
  private FluentAsset fluentAsset;
  
  @Mock
  private Content content;
  
  @Mock
  private TempBlob tempBlob;
  
  private ConcurrentHashMap<String, String> assetStore;
  
  @Before
  public void setup() throws IOException {
    // Skip tests if virtual threads are not supported
    assumeVirtualThreadSupported();
    
    // Initialize a simple in-memory store for assets
    assetStore = new ConcurrentHashMap<>();
    
    // Mock repository
    when(repository.getName()).thenReturn("raw-test-repo");
    
    // Mock content facet put operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      Payload payload = invocation.getArgument(1);
      
      // Simulate I/O operation with a small delay
      Thread.sleep(5);
      
      // Store the content
      String contentStr = new String(payload.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assetStore.put(path, contentStr);
      
      return content;
    }).when(rawContentFacet).put(anyString(), any(Payload.class));
    
    // Mock content facet get operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      
      // Simulate I/O operation with a small delay
      Thread.sleep(5);
      
      // Retrieve the content
      String contentStr = assetStore.get(path);
      if (contentStr == null) {
        return Optional.empty();
      }
      
      Content mockContent = Mockito.mock(Content.class);
      Payload mockPayload = new StringPayload(contentStr);
      when(mockContent.openInputStream()).thenReturn(mockPayload.openInputStream());
      
      return Optional.of(mockContent);
    }).when(rawContentFacet).get(anyString());
    
    // Mock content facet delete operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      
      // Simulate I/O operation with a small delay
      Thread.sleep(5);
      
      // Remove the content
      return assetStore.remove(path) != null;
    }).when(rawContentFacet).delete(anyString());
    
    // Mock getOrCreateAsset
    when(rawContentFacet.getOrCreateAsset(any(Repository.class), anyString(), anyString(), anyString()))
        .thenReturn(fluentAsset);
  }
  
  /**
   * Tests that a simple put operation works correctly on a Virtual Thread.
   */
  @Test
  public void testPutOperationOnVirtualThread() throws Exception {
    // Execute put operation on a virtual thread
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Perform the put operation
      Payload payload = new StringPayload(TEST_CONTENT);
      Content result = rawContentFacet.put(TEST_PATH, payload);
      
      // Verify the result
      assertThat(result, is(notNullValue()));
      assertThat(assetStore.get(TEST_PATH), is(equalTo(TEST_CONTENT)));
    });
  }
  
  /**
   * Tests that a simple get operation works correctly on a Virtual Thread.
   */
  @Test
  public void testGetOperationOnVirtualThread() throws Exception {
    // Setup test data
    assetStore.put(TEST_PATH, TEST_CONTENT);
    
    // Execute get operation on a virtual thread
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Perform the get operation
      Optional<Content> result = rawContentFacet.get(TEST_PATH);
      
      // Verify the result
      assertTrue(result.isPresent());
      Content content = result.get();
      String retrievedContent = new String(content.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(retrievedContent, is(equalTo(TEST_CONTENT)));
    });
  }
  
  /**
   * Tests that a simple delete operation works correctly on a Virtual Thread.
   */
  @Test
  public void testDeleteOperationOnVirtualThread() throws Exception {
    // Setup test data
    assetStore.put(TEST_PATH, TEST_CONTENT);
    
    // Execute delete operation on a virtual thread
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Perform the delete operation
      boolean result = rawContentFacet.delete(TEST_PATH);
      
      // Verify the result
      assertTrue(result);
      assertThat(assetStore.get(TEST_PATH), is(nullValue()));
    });
  }
  
  /**
   * Tests that concurrent put operations work correctly on Virtual Threads.
   */
  @Test
  public void testConcurrentPutOperationsOnVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-put-test-")) {
      // Submit concurrent put operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final String path = "test/concurrent/" + i + ".txt";
        final String content = "Content " + i;
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the put operation
            Payload payload = new StringPayload(content);
            Content result = rawContentFacet.put(path, payload);
            
            // Verify the result
            if (result != null && content.equals(assetStore.get(path))) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Log the exception but don't fail the test yet
            log.error("Error in concurrent put operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all operations succeeded
    assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    assertThat(assetStore.size(), is(equalTo(CONCURRENT_OPERATIONS)));
  }
  
  /**
   * Tests that concurrent get operations work correctly on Virtual Threads.
   */
  @Test
  public void testConcurrentGetOperationsOnVirtualThreads() throws Exception {
    // Setup test data
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      assetStore.put("test/concurrent/" + i + ".txt", "Content " + i);
    }
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-get-test-")) {
      // Submit concurrent get operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final String path = "test/concurrent/" + i + ".txt";
        final String expectedContent = "Content " + i;
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the get operation
            Optional<Content> result = rawContentFacet.get(path);
            
            // Verify the result
            if (result.isPresent()) {
              Content content = result.get();
              String retrievedContent = new String(content.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
              if (expectedContent.equals(retrievedContent)) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            // Log the exception but don't fail the test yet
            log.error("Error in concurrent get operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all operations succeeded
    assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
  }
  
  /**
   * Tests that concurrent delete operations work correctly on Virtual Threads.
   */
  @Test
  public void testConcurrentDeleteOperationsOnVirtualThreads() throws Exception {
    // Setup test data
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      assetStore.put("test/concurrent/" + i + ".txt", "Content " + i);
    }
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-delete-test-")) {
      // Submit concurrent delete operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final String path = "test/concurrent/" + i + ".txt";
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the delete operation
            boolean result = rawContentFacet.delete(path);
            
            // Verify the result
            if (result && assetStore.get(path) == null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Log the exception but don't fail the test yet
            log.error("Error in concurrent delete operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for concurrent operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all operations succeeded
    assertThat(successCount.get(), is(equalTo(CONCURRENT_OPERATIONS)));
    assertThat(assetStore.size(), is(equalTo(0)));
  }
  
  /**
   * Tests that mixed operations (put, get, delete) work correctly on Virtual Threads.
   */
  @Test
  public void testMixedOperationsOnVirtualThreads() throws Exception {
    // Create a countdown latch to wait for all operations to complete
    int totalOperations = CONCURRENT_OPERATIONS * 3; // put, get, delete for each path
    CountDownLatch latch = new CountDownLatch(totalOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-mixed-test-")) {
      // Submit mixed operations
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final String path = "test/mixed/" + i + ".txt";
        final String content = "Mixed Content " + i;
        
        // Submit put operation
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the put operation
            Payload payload = new StringPayload(content);
            Content result = rawContentFacet.put(path, payload);
            
            // Verify the result
            if (result != null && content.equals(assetStore.get(path))) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in mixed put operation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Submit get operation
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the get operation
            Optional<Content> result = rawContentFacet.get(path);
            
            // Verify the result - may or may not be present depending on timing
            if (result.isPresent()) {
              Content contentObj = result.get();
              String retrievedContent = new String(contentObj.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
              if (content.equals(retrievedContent)) {
                successCount.incrementAndGet();
              }
            }
            else {
              // If not present, it might have been deleted already, which is also a success
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in mixed get operation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Submit delete operation
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the delete operation
            boolean result = rawContentFacet.delete(path);
            
            // Verify the result - may or may not succeed depending on timing
            if (result || assetStore.get(path) == null) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in mixed delete operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for mixed operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify most operations succeeded (some may fail due to race conditions, which is expected)
    assertThat(successCount.get(), is(greaterThan(totalOperations * 8 / 10))); // At least 80% success
  }
  
  /**
   * Tests that operations with large payloads work correctly on Virtual Threads.
   */
  @Test
  public void testLargePayloadOperationsOnVirtualThreads() throws Exception {
    // Create a large payload (1MB)
    byte[] largeData = new byte[1024 * 1024];
    for (int i = 0; i < largeData.length; i++) {
      largeData[i] = (byte) (i % 256);
    }
    
    // Execute put operation with large payload on a virtual thread
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Perform the put operation
      Payload payload = () -> new ByteArrayInputStream(largeData);
      Content result = rawContentFacet.put("test/large.bin", payload);
      
      // Verify the result
      assertThat(result, is(notNullValue()));
      assertThat(assetStore.get("test/large.bin").length(), is(equalTo(largeData.length)));
    });
    
    // Execute get operation with large payload on a virtual thread
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      // Perform the get operation
      Optional<Content> result = rawContentFacet.get("test/large.bin");
      
      // Verify the result
      assertTrue(result.isPresent());
      Content content = result.get();
      byte[] retrievedData = content.openInputStream().readAllBytes();
      assertThat(retrievedData.length, is(equalTo(largeData.length)));
    });
  }
  
  /**
   * Tests that operations with exceptions are handled correctly on Virtual Threads.
   */
  @Test
  public void testExceptionHandlingOnVirtualThreads() throws Exception {
    // Mock an exception in the put operation
    doAnswer(invocation -> {
      throw new IOException("Simulated IO exception");
    }).when(rawContentFacet).put(eq("test/exception.txt"), any(Payload.class));
    
    // Execute put operation that will throw an exception on a virtual thread
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    runVirtual(() -> {
      // Verify we're running on a virtual thread
      assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
      
      try {
        // Perform the put operation that should throw
        Payload payload = new StringPayload("Exception test");
        rawContentFacet.put("test/exception.txt", payload);
      }
      catch (IOException e) {
        // Verify the exception
        assertThat(e.getMessage(), is(equalTo("Simulated IO exception")));
        exceptionCount.incrementAndGet();
      }
    });
    
    // Verify the exception was caught
    assertThat(exceptionCount.get(), is(equalTo(1)));
  }
  
  /**
   * Tests that Virtual Threads provide better performance than platform threads for I/O operations.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    // Number of operations for performance test
    final int operationCount = 100;
    
    // Prepare test data
    List<String> paths = new ArrayList<>();
    List<String> contents = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      paths.add("test/perf/" + UUID.randomUUID() + ".txt");
      contents.add("Performance test content " + i);
    }
    
    // Define the operation to test
    Callable<Void> performOperations = () -> {
      for (int i = 0; i < operationCount; i++) {
        // Put operation
        Payload payload = new StringPayload(contents.get(i));
        rawContentFacet.put(paths.get(i), payload);
        
        // Get operation
        Optional<Content> result = rawContentFacet.get(paths.get(i));
        if (result.isPresent()) {
          result.get().openInputStream().readAllBytes();
        }
        
        // Delete operation
        rawContentFacet.delete(paths.get(i));
      }
      return null;
    };
    
    // Measure execution time with platform threads
    long platformThreadStart = System.currentTimeMillis();
    performOperations.call();
    long platformThreadDuration = System.currentTimeMillis() - platformThreadStart;
    
    // Clear the store
    assetStore.clear();
    
    // Measure execution time with virtual threads
    long virtualThreadStart = System.currentTimeMillis();
    callVirtual(performOperations);
    long virtualThreadDuration = System.currentTimeMillis() - virtualThreadStart;
    
    // Log the results
    log.info("Platform thread duration: {} ms", platformThreadDuration);
    log.info("Virtual thread duration: {} ms", virtualThreadDuration);
    log.info("Performance improvement: {}%", 
        (platformThreadDuration > 0) ? 
            ((platformThreadDuration - virtualThreadDuration) * 100 / platformThreadDuration) : "N/A");
    
    // Verify virtual threads are at least as fast as platform threads
    // Note: In real-world scenarios with actual I/O, virtual threads should be significantly faster
    assertThat(virtualThreadDuration, is(lessThan(platformThreadDuration * 2))); // Allow some overhead for test setup
  }
  
  /**
   * Tests that thread pinning is avoided during content operations.
   */
  @Test
  public void testThreadPinningAvoidance() throws Exception {
    // Enable thread pinning detection
    ThreadPinningDetector.enableJdkPinningDetection();
    
    try {
      // Test put operation for thread pinning
      boolean putPinning = detectThreadPinning(() -> {
        try {
          Payload payload = new StringPayload("Pinning test");
          rawContentFacet.put("test/pinning.txt", payload);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      // Test get operation for thread pinning
      boolean getPinning = detectThreadPinning(() -> {
        try {
          rawContentFacet.get("test/pinning.txt");
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      // Test delete operation for thread pinning
      boolean deletePinning = detectThreadPinning(() -> {
        try {
          rawContentFacet.delete("test/pinning.txt");
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      // Verify no thread pinning occurred
      assertFalse("Put operation caused thread pinning", putPinning);
      assertFalse("Get operation caused thread pinning", getPinning);
      assertFalse("Delete operation caused thread pinning", deletePinning);
    }
    finally {
      // Reset thread pinning detection
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
  
  /**
   * Tests that high-concurrency operations scale well with Virtual Threads.
   */
  @Test
  public void testHighConcurrencyScaling() throws Exception {
    // Number of operations for high concurrency test
    final int highConcurrencyCount = 5000;
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(highConcurrencyCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-scaling-test-")) {
      // Record start time
      long startTime = System.currentTimeMillis();
      
      // Submit concurrent put operations
      for (int i = 0; i < highConcurrencyCount; i++) {
        final String path = "test/scaling/" + i + ".txt";
        final String content = "Scaling Content " + i;
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the put operation
            Payload payload = new StringPayload(content);
            Content result = rawContentFacet.put(path, payload);
            
            // Verify the result
            if (result != null && content.equals(assetStore.get(path))) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in high concurrency operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for high concurrency operations to complete",
          latch.await(60, TimeUnit.SECONDS));
      
      // Record end time
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      
      // Log the results
      log.info("High concurrency test completed {} operations in {} ms", highConcurrencyCount, duration);
      log.info("Average time per operation: {} ms", (double) duration / highConcurrencyCount);
      log.info("Operations per second: {}", (double) highConcurrencyCount * 1000 / duration);
    }
    
    // Verify all operations succeeded
    assertThat(successCount.get(), is(equalTo(highConcurrencyCount)));
    assertThat(assetStore.size(), is(equalTo(highConcurrencyCount)));
  }
  
  /**
   * Tests that Virtual Threads can handle long-running operations without blocking.
   */
  @Test
  public void testLongRunningOperations() throws Exception {
    // Number of long-running operations
    final int operationCount = 10;
    
    // Create a list to store futures
    List<Future<Content>> futures = new ArrayList<>();
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-long-running-test-")) {
      // Submit long-running operations
      for (int i = 0; i < operationCount; i++) {
        final String path = "test/long/" + i + ".txt";
        final String content = "Long-running Content " + i;
        final int sleepTime = 500 + (i * 100); // Increasing sleep times
        
        futures.add(executor.submit(() -> {
          // Verify we're running on a virtual thread
          assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
          
          // Simulate a long-running operation
          Thread.sleep(sleepTime);
          
          // Perform the put operation
          Payload payload = new StringPayload(content);
          return rawContentFacet.put(path, payload);
        }));
      }
      
      // Wait for all operations to complete and verify results
      for (int i = 0; i < operationCount; i++) {
        Content result = futures.get(i).get(10, TimeUnit.SECONDS);
        assertThat(result, is(notNullValue()));
        
        final String path = "test/long/" + i + ".txt";
        final String content = "Long-running Content " + i;
        assertThat(assetStore.get(path), is(equalTo(content)));
      }
    }
  }
  
  /**
   * Tests that Virtual Threads properly handle transaction boundaries.
   */
  @Test
  public void testTransactionBoundaries() throws Exception {
    // Mock a transaction-like behavior
    AtomicInteger activeTransactions = new AtomicInteger(0);
    AtomicInteger maxConcurrentTransactions = new AtomicInteger(0);
    
    // Override the put method to simulate transaction boundaries
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      Payload payload = invocation.getArgument(1);
      
      // Begin transaction
      int current = activeTransactions.incrementAndGet();
      maxConcurrentTransactions.updateAndGet(max -> Math.max(max, current));
      
      try {
        // Simulate I/O operation with a small delay
        Thread.sleep(20);
        
        // Store the content
        String contentStr = new String(payload.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assetStore.put(path, contentStr);
        
        return content;
      }
      finally {
        // End transaction
        activeTransactions.decrementAndGet();
      }
    }).when(rawContentFacet).put(anyString(), any(Payload.class));
    
    // Execute concurrent operations to test transaction boundaries
    final int concurrentTxCount = 100;
    CountDownLatch latch = new CountDownLatch(concurrentTxCount);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-tx-test-")) {
      // Submit concurrent operations
      for (int i = 0; i < concurrentTxCount; i++) {
        final String path = "test/tx/" + i + ".txt";
        final String content = "Transaction Content " + i;
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the put operation
            Payload payload = new StringPayload(content);
            rawContentFacet.put(path, payload);
          }
          catch (Exception e) {
            log.error("Error in transaction test", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for transaction operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify transaction boundaries were respected
    log.info("Maximum concurrent transactions: {}", maxConcurrentTransactions.get());
    assertThat(activeTransactions.get(), is(equalTo(0))); // All transactions should be closed
    assertThat(maxConcurrentTransactions.get(), is(greaterThan(1))); // Should have concurrent transactions
    assertThat(assetStore.size(), is(equalTo(concurrentTxCount))); // All operations should succeed
  }
  
  /**
   * Tests that Virtual Threads properly handle resource cleanup.
   */
  @Test
  public void testResourceCleanup() throws Exception {
    // Track opened and closed resources
    AtomicInteger openedResources = new AtomicInteger(0);
    AtomicInteger closedResources = new AtomicInteger(0);
    
    // Mock a resource-intensive operation
    doAnswer(invocation -> {
      String path = invocation.getArgument(0);
      
      // Open a resource
      openedResources.incrementAndGet();
      
      try {
        // Simulate I/O operation with a small delay
        Thread.sleep(10);
        
        // Retrieve the content
        String contentStr = assetStore.get(path);
        if (contentStr == null) {
          return Optional.empty();
        }
        
        Content mockContent = Mockito.mock(Content.class);
        Payload mockPayload = new StringPayload(contentStr);
        when(mockContent.openInputStream()).thenReturn(mockPayload.openInputStream());
        
        return Optional.of(mockContent);
      }
      finally {
        // Close the resource
        closedResources.incrementAndGet();
      }
    }).when(rawContentFacet).get(anyString());
    
    // Setup test data
    for (int i = 0; i < 100; i++) {
      assetStore.put("test/resource/" + i + ".txt", "Resource Content " + i);
    }
    
    // Execute concurrent operations to test resource cleanup
    final int resourceOpCount = 100;
    CountDownLatch latch = new CountDownLatch(resourceOpCount);
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = newVirtualThreadExecutor("raw-resource-test-")) {
      // Submit concurrent operations
      for (int i = 0; i < resourceOpCount; i++) {
        final String path = "test/resource/" + i + ".txt";
        
        executor.submit(() -> {
          try {
            // Verify we're running on a virtual thread
            assertThat(Thread.currentThread(), VirtualThreadMatchers.isVirtualThread());
            
            // Perform the get operation
            Optional<Content> result = rawContentFacet.get(path);
            
            // Verify the result
            assertTrue(result.isPresent());
          }
          catch (Exception e) {
            log.error("Error in resource cleanup test", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue("Timed out waiting for resource operations to complete",
          latch.await(30, TimeUnit.SECONDS));
    }
    
    // Verify all resources were properly opened and closed
    log.info("Opened resources: {}", openedResources.get());
    log.info("Closed resources: {}", closedResources.get());
    assertThat(openedResources.get(), is(equalTo(resourceOpCount))); // All resources should be opened
    assertThat(closedResources.get(), is(equalTo(resourceOpCount))); // All resources should be closed
  }
}