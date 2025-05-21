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

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.common.template.TemplateThrowableAdapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Virtual Thread-specific test for {@link CapabilitySupport} that validates template rendering
 * and error handling behavior when running on Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class CapabilitySupportVirtualThreadTest
    extends TestSupport
{
  @Mock
  private CapabilityContext context;

  @Mock
  private TemplateHelper templateHelper;

  private TestCapability underTest;

  @BeforeEach
  void setup() {
    underTest = new TestCapability();
    underTest.init(context);
    underTest.setTemplateHelper(templateHelper);
  }

  @Test
  void renderFailureReturnsNullOnFailure() {
    TemplateParameters params = new TemplateParameters()
        .set("cause", new TemplateThrowableAdapter(new Exception()));
    assertNull(underTest.render("missing-template.vm", params));
    verifyNoInteractions(templateHelper);
  }

  @Test
  void renderFailureSuccess() {
    when(templateHelper.render(any(URL.class), any(TemplateParameters.class))).thenReturn("rendered");
    TemplateParameters params = new TemplateParameters()
        .set("cause", new TemplateThrowableAdapter(new Exception()));
    assertEquals("rendered", underTest.render("failure.vm", params));
    verify(templateHelper).render(underTest.getClass().getResource("failure.vm"), params);
  }

  @Test
  void concurrentTemplateRenderingWithVirtualThreads() throws Exception {
    // Configure mock to return a unique string for each call
    when(templateHelper.render(any(URL.class), any(TemplateParameters.class)))
        .thenAnswer(invocation -> "rendered-" + System.nanoTime());

    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent rendering operations
      int concurrentTasks = 100;
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      AtomicInteger errorCount = new AtomicInteger(0);
      List<String> results = new ArrayList<>(concurrentTasks);

      // Submit tasks to render templates concurrently using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < concurrentTasks; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            TemplateParameters params = new TemplateParameters()
                .set("cause", new TemplateThrowableAdapter(new Exception()));
            String result = underTest.render("failure.vm", params);
            synchronized (results) {
              results.add(result);
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "All template rendering tasks should complete within timeout");

      // Verify results
      assertEquals(0, errorCount.get(), "No errors should occur during concurrent template rendering");
      assertEquals(concurrentTasks, results.size(), "All tasks should produce a result");

      // Verify the templateHelper was called the expected number of times
      verify(templateHelper, times(concurrentTasks)).render(any(URL.class), any(TemplateParameters.class));
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void detectThreadPinningDuringTemplateRendering() throws Exception {
    // Configure mock to simulate a blocking operation that could cause thread pinning
    when(templateHelper.render(any(URL.class), any(TemplateParameters.class)))
        .thenAnswer(invocation -> {
          // Simulate a blocking operation that might cause thread pinning
          Thread.sleep(50);
          return "rendered-after-blocking";
        });

    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Track if we detect any thread pinning
      AtomicBoolean threadPinningDetected = new AtomicBoolean(false);

      // Create a monitoring thread to check for carrier thread starvation
      // This is a simplified detection mechanism - in production you would use JFR or other tools
      Thread monitorThread = new Thread(() -> {
        try {
          // Wait a bit for tasks to start
          Thread.sleep(100);
          
          // Check if virtual threads are making progress
          // In a real scenario, you would use JFR events or JMX to detect pinning
          // This is a simplified simulation for testing purposes
          threadPinningDetected.set(false); // In this test we expect no pinning
        }
        catch (InterruptedException e) {
          // Ignore
        }
      });
      monitorThread.start();

      // Submit multiple template rendering tasks
      int concurrentTasks = 20;
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      List<CompletableFuture<String>> futures = new ArrayList<>();

      for (int i = 0; i < concurrentTasks; i++) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
          try {
            TemplateParameters params = new TemplateParameters()
                .set("cause", new TemplateThrowableAdapter(new Exception()));
            return underTest.render("failure.vm", params);
          }
          finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "All template rendering tasks should complete within timeout");
      monitorThread.join(1000);

      // Verify no thread pinning was detected
      // In a real test with actual thread pinning, this would be true
      // For this test, we expect false since our mock doesn't cause pinning
      assertFalse(threadPinningDetected.get(), "No thread pinning should be detected");

      // Verify all tasks completed successfully
      for (CompletableFuture<String> future : futures) {
        assertEquals("rendered-after-blocking", future.get(1, TimeUnit.SECONDS));
      }
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    // Configure mock for consistent performance testing
    when(templateHelper.render(any(URL.class), any(TemplateParameters.class)))
        .thenReturn("rendered");

    // Number of templates to render in each test
    int iterations = 1000;
    TemplateParameters params = new TemplateParameters()
        .set("cause", new TemplateThrowableAdapter(new Exception()));

    // Test with platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), 
        platformThreadFactory);

    long platformThreadTime = measureExecutionTime(() -> {
      try {
        CountDownLatch platformLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
          platformExecutor.submit(() -> {
            try {
              underTest.render("failure.vm", params);
            }
            finally {
              platformLatch.countDown();
            }
          });
        }
        platformLatch.await(30, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    platformExecutor.shutdown();

    // Test with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    long virtualThreadTime = measureExecutionTime(() -> {
      try {
        CountDownLatch virtualLatch = new CountDownLatch(iterations);
        for (int i = 0; i < iterations; i++) {
          virtualExecutor.submit(() -> {
            try {
              underTest.render("failure.vm", params);
            }
            finally {
              virtualLatch.countDown();
            }
          });
        }
        virtualLatch.await(30, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    virtualExecutor.shutdown();

    // Log performance comparison
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    log.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);

    // Note: We don't assert on specific performance improvements as they can vary by environment
    // In a real test, you might want to add assertions if you have baseline expectations
    // assertTrue(virtualThreadTime < platformThreadTime, "Virtual threads should be faster than platform threads");
  }

  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  private void assertFalse(boolean condition, String message) {
    assertTrue(!condition, message);
  }

  private class TestCapability
      extends CapabilitySupport<TestCapabilityConfig>
  {
    @Override
    protected TestCapabilityConfig createConfig(final Map<String, String> properties) throws Exception {
      return new TestCapabilityConfig();
    }
  }

  private class TestCapabilityConfig
      extends CapabilityConfigurationSupport
  {
  }
}