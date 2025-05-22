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
package org.sonatype.nexus.mime.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.MimeRule;

import com.google.common.base.Joiner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link NexusMimeTypes} under Java 21 Virtual Threads.
 * 
 * This test validates that the NexusMimeTypes component correctly handles concurrent
 * access from many Virtual Threads, ensuring thread safety and consistent behavior
 * under high concurrency loads.
 */
public class NexusMimeTypesVirtualThreadTest
    extends TestSupport
{
  private NexusMimeTypes underTest;
  private ExecutorService executorService;

  @BeforeEach
  void setUp() {
    underTest = new NexusMimeTypes();
    
    // Create a virtual thread per task executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    executorService = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  @AfterEach
  void tearDown() {
    if (executorService != null) {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  private Properties addMimeType(final Properties properties, final String extension, final String... types) {
    properties.setProperty(extension, Joiner.on(",").join(types));
    return properties;
  }

  /**
   * Tests that NexusMimeTypes correctly handles concurrent lookups from many Virtual Threads.
   * This validates thread safety of the extension-to-MIME-type cache under high concurrency.
   */
  @Test
  void testConcurrentMimeTypeLookups() throws Exception {
    // Initialize with some MIME types
    Properties properties = new Properties();
    addMimeType(properties, "test", "application/octet-stream");
    addMimeType(properties, "html", "text/html");
    addMimeType(properties, "json", "application/json");
    addMimeType(properties, "xml", "application/xml");
    addMimeType(properties, "txt", "text/plain");
    addMimeType(properties, "override.pdf", "application/pdf");
    underTest.initMimeTypes(properties);

    // Number of virtual threads to create
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Track any inconsistent results
    ConcurrentHashMap<String, List<String>> results = new ConcurrentHashMap<>();

    // Submit tasks to virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int iteration = i;
      executorService.submit(() -> {
        try {
          // Cycle through different extensions to test cache behavior
          String extension = switch (iteration % 6) {
            case 0 -> "test";
            case 1 -> "html";
            case 2 -> "json";
            case 3 -> "xml";
            case 4 -> "txt";
            case 5 -> "pdf";
            default -> "test";
          };
          
          // Get the MIME rule for this extension
          MimeRule mimeRule = underTest.getMimeRuleForExtension(extension);
          
          // Record the result for verification
          if (mimeRule != null) {
            String mimeTypes = String.join(",", mimeRule.getMimetypes());
            results.computeIfAbsent(extension, k -> new ArrayList<>()).add(mimeTypes);
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in virtual thread {}", Thread.currentThread().getName(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete in time", completed, is(true));
    assertThat("No errors should occur during concurrent access", errorCount.get(), is(0));

    // Verify results are consistent for each extension
    results.forEach((extension, mimeTypesList) -> {
      assertThat("All lookups for extension " + extension + " should return the same result",
          mimeTypesList.stream().distinct().count(), is(1L));
    });
    
    // Verify specific MIME types
    assertThat(underTest.getMimeRuleForExtension("test"), hasProperty("override", is(false)));
    assertThat(underTest.getMimeRuleForExtension("test").getMimetypes(), contains("application/octet-stream"));
    
    assertThat(underTest.getMimeRuleForExtension("pdf"), hasProperty("override", is(true)));
    assertThat(underTest.getMimeRuleForExtension("pdf").getMimetypes(), contains("application/pdf"));
  }

  /**
   * Tests that NexusMimeTypes correctly handles concurrent initialization and lookups.
   * This validates thread safety during configuration changes.
   */
  @Test
  void testConcurrentInitializationAndLookup() throws Exception {
    // Number of virtual threads to create
    int threadCount = 500;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();

    // Submit tasks to virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int iteration = i;
      executorService.submit(() -> {
        try {
          if (iteration % 5 == 0) {
            // Every 5th thread will update the MIME types
            Properties properties = new Properties();
            addMimeType(properties, "test" + iteration, "application/test-" + iteration);
            addMimeType(properties, "override.pdf", "application/pdf-" + iteration);
            underTest.initMimeTypes(properties);
          }
          else {
            // Other threads will perform lookups
            underTest.getMimeRuleForExtension("test");
            underTest.getMimeRuleForExtension("pdf");
            underTest.getMimeRuleForExtension("html");
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          firstException.compareAndSet(null, e);
          log.error("Error in virtual thread {}", Thread.currentThread().getName(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete in time", completed, is(true));
    
    if (errorCount.get() > 0 && firstException.get() != null) {
      throw new AssertionError("Errors occurred during concurrent access: " + errorCount.get(), 
          firstException.get());
    }
    
    assertThat("No errors should occur during concurrent access", errorCount.get(), is(0));
  }

  /**
   * Tests that NexusMimeTypes correctly handles a very large number of concurrent lookups
   * from Virtual Threads. This validates scalability under extreme concurrency.
   */
  @Test
  void testHighConcurrencyMimeTypeLookups() throws Exception {
    // Initialize with some MIME types
    Properties properties = new Properties();
    for (int i = 0; i < 100; i++) {
      addMimeType(properties, "ext" + i, "application/type-" + i);
    }
    addMimeType(properties, "override.special", "application/special");
    underTest.initMimeTypes(properties);

    // Create a very large number of virtual threads
    int threadCount = 10_000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Submit tasks to virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int iteration = i;
      executorService.submit(() -> {
        try {
          // Distribute lookups across different extensions
          String extension = "ext" + (iteration % 100);
          MimeRule mimeRule = underTest.getMimeRuleForExtension(extension);
          
          // Verify the result is as expected
          if (mimeRule == null) {
            errorCount.incrementAndGet();
          }
          else {
            String expectedMimeType = "application/type-" + (iteration % 100);
            if (!mimeRule.getMimetypes().contains(expectedMimeType)) {
              errorCount.incrementAndGet();
            }
          }
          
          // Also test the override extension
          if (iteration % 200 == 0) {
            MimeRule specialRule = underTest.getMimeRuleForExtension("special");
            if (specialRule == null || !specialRule.getMimetypes().contains("application/special")) {
              errorCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in virtual thread {}", Thread.currentThread().getName(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all threads to complete
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete in time", completed, is(true));
    assertThat("No errors should occur during high concurrency access", errorCount.get(), is(0));
  }
}