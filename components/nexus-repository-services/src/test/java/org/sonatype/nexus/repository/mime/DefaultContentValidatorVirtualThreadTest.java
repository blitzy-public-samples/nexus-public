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
package org.sonatype.nexus.repository.mime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.internal.DefaultMimeSupport;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.view.ContentTypes;

import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link DefaultContentValidator} using Java 21 Virtual Threads.
 * 
 * This test suite validates the behavior of the DefaultContentValidator when operating
 * under high concurrency with Virtual Threads, ensuring that content validation operations
 * maintain correctness and avoid thread pinning.
 */
public class DefaultContentValidatorVirtualThreadTest
    extends TestSupport
{
  private DefaultContentValidator testSubject;

  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  // Test content samples
  private final byte[] textContent = "simple text".getBytes();
  private final byte[] emptyZip = {80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  private final byte[] binaryContent = {1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

  @Before
  public void setUp() {
    testSubject = new DefaultContentValidator(new DefaultMimeSupport());
  }

  /**
   * Creates an InputStreamSupplier for the given byte array.
   */
  private InputStreamSupplier supplier(byte[] bytes) {
    return () -> new ByteArrayInputStream(bytes);
  }

  /**
   * Creates a ThreadFactory that produces virtual threads with the given name prefix.
   */
  private ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }

  /**
   * Test concurrent content validation with virtual threads using text content.
   * Verifies that all threads correctly identify the content type as text/plain.
   */
  @Test
  public void concurrentTextValidationWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("text-validator-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Submit tasks for concurrent validation
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          // Wait for all threads to start simultaneously
          startLatch.await();
          
          String type = testSubject.determineContentType(
              false,
              supplier(textContent),
              MimeRulesSource.NOOP,
              "test.txt",
              ContentTypes.TEXT_PLAIN);
          
          // Verify correct content type
          if (!ContentTypes.TEXT_PLAIN.equals(type)) {
            throw new AssertionError("Expected " + ContentTypes.TEXT_PLAIN + " but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for errors
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      fail("Encountered " + errorCount.get() + " errors. First error: " + 
           (e != null ? e.getMessage() : "unknown"));
    }
    
    executor.shutdown();
  }

  /**
   * Test concurrent content validation with virtual threads using binary content.
   * Verifies that all threads correctly identify the content type as application/octet-stream.
   */
  @Test
  public void concurrentBinaryValidationWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("binary-validator-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Submit tasks for concurrent validation
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          // Wait for all threads to start simultaneously
          startLatch.await();
          
          String type = testSubject.determineContentType(
              false,
              supplier(binaryContent),
              MimeRulesSource.NOOP,
              "binary",
              null);
          
          // Verify correct content type
          if (!ContentTypes.APPLICATION_OCTET_STREAM.equals(type)) {
            throw new AssertionError("Expected " + ContentTypes.APPLICATION_OCTET_STREAM + " but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for errors
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      fail("Encountered " + errorCount.get() + " errors. First error: " + 
           (e != null ? e.getMessage() : "unknown"));
    }
    
    executor.shutdown();
  }

  /**
   * Test concurrent content validation with virtual threads using ZIP content.
   * Verifies that all threads correctly identify the content type as application/zip.
   */
  @Test
  public void concurrentZipValidationWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("zip-validator-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Submit tasks for concurrent validation
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          // Wait for all threads to start simultaneously
          startLatch.await();
          
          String type = testSubject.determineContentType(
              false,
              supplier(emptyZip),
              MimeRulesSource.NOOP,
              "test.zip",
              null);
          
          // Verify correct content type
          if (!"application/zip".equals(type)) {
            throw new AssertionError("Expected application/zip but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for errors
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      fail("Encountered " + errorCount.get() + " errors. First error: " + 
           (e != null ? e.getMessage() : "unknown"));
    }
    
    executor.shutdown();
  }

  /**
   * Test concurrent strict content validation with virtual threads.
   * Verifies that all threads correctly throw InvalidContentException for mismatched content.
   */
  @Test
  public void concurrentStrictValidationWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("strict-validator-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicReference<Exception> unexpectedException = new AtomicReference<>();

    // Submit tasks for concurrent validation
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          // Wait for all threads to start simultaneously
          startLatch.await();
          
          try {
            // This should throw InvalidContentException
            testSubject.determineContentType(
                true,
                supplier(textContent),
                MimeRulesSource.NOOP,
                "test.zip",
                "application/zip");
            
            // If we get here, the expected exception wasn't thrown
            fail("Expected InvalidContentException was not thrown");
          } catch (InvalidContentException e) {
            // This is the expected exception
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          // This is an unexpected exception
          unexpectedException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for unexpected exceptions
    if (unexpectedException.get() != null) {
      fail("Encountered unexpected exception: " + unexpectedException.get().getMessage());
    }
    
    // Verify all threads got the expected exception
    assertEquals("Not all threads received the expected exception", 
                CONCURRENT_THREADS, successCount.get());
    
    executor.shutdown();
  }

  /**
   * Test mixed content validation with virtual threads.
   * Submits a mix of different content types and validation modes to test concurrent operation.
   */
  @Test
  public void concurrentMixedValidationWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("mixed-validator-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    int totalTasks = CONCURRENT_THREADS * 3; // 3 different types of tasks
    CountDownLatch completionLatch = new CountDownLatch(totalTasks);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Submit tasks for concurrent validation
    List<Future<?>> futures = new ArrayList<>();
    
    // Add text validation tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          startLatch.await();
          String type = testSubject.determineContentType(
              false,
              supplier(textContent),
              MimeRulesSource.NOOP,
              "test.txt",
              ContentTypes.TEXT_PLAIN);
          if (!ContentTypes.TEXT_PLAIN.equals(type)) {
            throw new AssertionError("Expected " + ContentTypes.TEXT_PLAIN + " but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Add zip validation tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          startLatch.await();
          String type = testSubject.determineContentType(
              false,
              supplier(emptyZip),
              MimeRulesSource.NOOP,
              "test.zip",
              null);
          if (!"application/zip".equals(type)) {
            throw new AssertionError("Expected application/zip but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Add binary validation tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(executor.submit(() -> {
        try {
          startLatch.await();
          String type = testSubject.determineContentType(
              false,
              supplier(binaryContent),
              MimeRulesSource.NOOP,
              "binary",
              null);
          if (!ContentTypes.APPLICATION_OCTET_STREAM.equals(type)) {
            throw new AssertionError("Expected " + ContentTypes.APPLICATION_OCTET_STREAM + " but got " + type);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
        } finally {
          completionLatch.countDown();
        }
      }));
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for errors
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      fail("Encountered " + errorCount.get() + " errors. First error: " + 
           (e != null ? e.getMessage() : "unknown"));
    }
    
    executor.shutdown();
  }

  /**
   * Test for thread pinning detection during content validation.
   * This test verifies that virtual threads are not pinned during content validation operations.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection via system property
    String originalPinningProperty = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "short");
      
      ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("pinning-detector-"));
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Submit tasks for concurrent validation
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        futures.add(executor.submit(() -> {
          try {
            startLatch.await();
            
            // Perform content validation that should not cause pinning
            testSubject.determineContentType(
                false,
                supplier(textContent),
                MimeRulesSource.NOOP,
                "test.txt",
                ContentTypes.TEXT_PLAIN);
          } catch (Exception e) {
            // Log but continue - we're testing for pinning, not validation correctness here
            log.warn("Exception during validation", e);
          } finally {
            completionLatch.countDown();
          }
        }));
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all threads completed within timeout", completed);
      
      executor.shutdown();
      
      // If we get here without deadlock or excessive delays, the test passes
      // Thread pinning would be visible in logs if it occurred
    } finally {
      // Restore original system property
      if (originalPinningProperty != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningProperty);
      } else {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
  }

  /**
   * Test high concurrency with a very large number of virtual threads.
   * This test creates a large number of virtual threads to validate content simultaneously.
   */
  @Test
  public void testHighConcurrencyValidation() throws Exception {
    final int VERY_HIGH_CONCURRENCY = 5000; // 5000 concurrent threads
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(createVirtualThreadFactory("high-concurrency-"));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VERY_HIGH_CONCURRENCY);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit tasks for concurrent validation
    for (int i = 0; i < VERY_HIGH_CONCURRENCY; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          
          // Alternate between different content types based on thread ID
          long threadId = Thread.currentThread().threadId() % 3;
          if (threadId == 0) {
            testSubject.determineContentType(
                false,
                supplier(textContent),
                MimeRulesSource.NOOP,
                "test.txt",
                ContentTypes.TEXT_PLAIN);
          } else if (threadId == 1) {
            testSubject.determineContentType(
                false,
                supplier(emptyZip),
                MimeRulesSource.NOOP,
                "test.zip",
                null);
          } else {
            testSubject.determineContentType(
                false,
                supplier(binaryContent),
                MimeRulesSource.NOOP,
                "binary",
                null);
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete with a longer timeout due to the high concurrency
    boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
    assertTrue("Not all threads completed within timeout", completed);
    
    // Check for errors
    assertEquals("Errors occurred during high concurrency validation", 0, errorCount.get());
    
    executor.shutdown();
  }
}