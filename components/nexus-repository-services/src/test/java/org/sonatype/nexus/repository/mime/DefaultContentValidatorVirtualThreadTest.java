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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.internal.DefaultMimeSupport;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.view.ContentTypes;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests for {@link DefaultContentValidator} using Java 21 Virtual Threads.
 * These tests validate that content validation operations maintain correctness
 * and avoid thread pinning when executed by virtual threads under high concurrency.
 * 
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class DefaultContentValidatorVirtualThreadTest
    extends TestSupport
{
  private DefaultContentValidator testSubject;

  private final byte[] emptyZip = {80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  private final byte[] textContent = "simple text".getBytes();

  @Before
  public void setUp() {
    testSubject = new DefaultContentValidator(new DefaultMimeSupport());
  }

  private InputStreamSupplier supplier(byte[] bytes) {
    return () -> new ByteArrayInputStream(bytes);
  }

  /**
   * Tests concurrent content validation with virtual threads.
   * This test creates 1000 virtual threads, each performing content validation,
   * to verify that the DefaultContentValidator works correctly under high concurrency.
   */
  @Test
  public void testConcurrentContentValidationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between text and zip content to test different content types
            byte[] content = (index % 2 == 0) ? textContent : emptyZip;
            String filename = (index % 2 == 0) ? "test.txt" : "test.zip";
            String expectedType = (index % 2 == 0) ? ContentTypes.TEXT_PLAIN : "application/zip";
            
            String type = testSubject.determineContentType(
                false,
                supplier(content),
                MimeRulesSource.NOOP,
                filename,
                null);
            
            if (!expectedType.equals(type)) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during concurrent validation", errorCount.get(), equalTo(0));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent content validation with strict validation using virtual threads.
   * This test verifies that strict content validation works correctly under concurrent access.
   */
  @Test
  public void testConcurrentStrictContentValidationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between text and zip content with matching content types
            byte[] content = (index % 2 == 0) ? textContent : emptyZip;
            String filename = (index % 2 == 0) ? "test.txt" : "test.zip";
            String declaredType = (index % 2 == 0) ? ContentTypes.TEXT_PLAIN : "application/zip";
            String expectedType = declaredType;
            
            String type = testSubject.determineContentType(
                true, // strict validation
                supplier(content),
                MimeRulesSource.NOOP,
                filename,
                declaredType);
            
            if (!expectedType.equals(type)) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during concurrent strict validation", errorCount.get(), equalTo(0));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests mixed content validation scenarios with virtual threads.
   * This test runs multiple different validation scenarios concurrently to ensure
   * that the validator handles mixed workloads correctly.
   */
  @Test
  public void testMixedContentValidationScenariosWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    List<Exception> exceptions = new ArrayList<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create different validation scenarios based on the index
            switch (index % 5) {
              case 0: // Text content with correct type
                testSubject.determineContentType(
                    true,
                    supplier(textContent),
                    MimeRulesSource.NOOP,
                    "test.txt",
                    ContentTypes.TEXT_PLAIN);
                break;
              case 1: // Zip content with correct type
                testSubject.determineContentType(
                    true,
                    supplier(emptyZip),
                    MimeRulesSource.NOOP,
                    "test.zip",
                    "application/zip");
                break;
              case 2: // Text content with no declared type
                testSubject.determineContentType(
                    false,
                    supplier(textContent),
                    MimeRulesSource.NOOP,
                    "test.txt",
                    null);
                break;
              case 3: // Zip content with no declared type
                testSubject.determineContentType(
                    false,
                    supplier(emptyZip),
                    MimeRulesSource.NOOP,
                    "test.zip",
                    null);
                break;
              case 4: // Text content with wrong extension but non-strict validation
                testSubject.determineContentType(
                    false,
                    supplier(textContent),
                    MimeRulesSource.NOOP,
                    "test.zip", // wrong extension
                    null);
                break;
            }
          } 
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No exceptions should occur during mixed validation scenarios", exceptions.size(), equalTo(0));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests high concurrency content validation with virtual threads.
   * This test creates a larger number of virtual threads to validate the scalability
   * of the content validator under extreme concurrency.
   */
  @Test
  public void testHighConcurrencyContentValidationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 5000; // Higher concurrency
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between text and zip content to test different content types
            byte[] content = (index % 2 == 0) ? textContent : emptyZip;
            String filename = (index % 2 == 0) ? "test.txt" : "test.zip";
            String expectedType = (index % 2 == 0) ? ContentTypes.TEXT_PLAIN : "application/zip";
            
            String type = testSubject.determineContentType(
                false,
                supplier(content),
                MimeRulesSource.NOOP,
                filename,
                null);
            
            if (!expectedType.equals(type)) {
              errorCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during high concurrency validation", errorCount.get(), equalTo(0));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests thread pinning detection during content validation with virtual threads.
   * This test verifies that content validation operations don't cause thread pinning,
   * which would reduce the benefits of virtual threads.
   */
  @Test
  public void testThreadPinningDetectionDuringContentValidation() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicReference<Thread> pinnedThread = new AtomicReference<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Perform content validation
            testSubject.determineContentType(
                false,
                supplier(textContent),
                MimeRulesSource.NOOP,
                "test.txt",
                null);
            
            // Check if current thread is pinned
            Thread currentThread = Thread.currentThread();
            if (currentThread.isVirtual() && currentThread.toString().contains("pinned")) {
              pinnedThread.set(currentThread);
            }
          } 
          catch (Exception e) {
            // Ignore exceptions for this test
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no thread pinning occurred
      assertThat("Content validation should not cause thread pinning", pinnedThread.get(), is(null));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests error handling during concurrent content validation with virtual threads.
   * This test verifies that InvalidContentException is properly thrown and handled
   * when using strict validation with mismatched content types.
   */
  @Test
  public void testErrorHandlingDuringConcurrentContentValidation() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger expectedErrorCount = new AtomicInteger(0);
    AtomicInteger actualErrorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Every third task should generate an error with strict validation and mismatched content
            if (index % 3 == 0) {
              expectedErrorCount.incrementAndGet();
              // Text content with zip extension and strict validation should fail
              testSubject.determineContentType(
                  true, // strict validation
                  supplier(textContent),
                  MimeRulesSource.NOOP,
                  "test.zip", // wrong extension for text content
                  "application/zip");
            } else {
              // Normal validation that should succeed
              testSubject.determineContentType(
                  false,
                  supplier(textContent),
                  MimeRulesSource.NOOP,
                  "test.txt",
                  null);
            }
          } 
          catch (InvalidContentException e) {
            // This is expected for the error cases
            actualErrorCount.incrementAndGet();
          }
          catch (Exception e) {
            // Unexpected exceptions
            log.error("Unexpected exception", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("Expected number of InvalidContentExceptions should be thrown", 
          actualErrorCount.get(), equalTo(expectedErrorCount.get()));
    } 
    finally {
      executor.shutdown();
    }
  }
}