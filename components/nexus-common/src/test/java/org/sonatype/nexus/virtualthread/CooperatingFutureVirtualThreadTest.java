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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.io.CooperatingFuture;
import org.sonatype.nexus.common.io.CooperationException;
import org.sonatype.nexus.common.io.CooperationFactorySupport.Config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link CooperatingFuture} with Virtual Threads.
 * 
 * @since 3.60
 */
public class CooperatingFutureVirtualThreadTest
    extends TestSupport
{
  private ExecutorService executor;

  @Before
  public void setUp() {
    // Use Virtual Thread executor for tests
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @After
  public void tearDown() {
    executor.shutdownNow();
  }

  @Test
  public void testVirtualThreadAsyncExecution() throws Exception {
    Config config = new Config();
    config.useVirtualThreads = true;
    
    CooperatingFuture<String> future = new CooperatingFuture<>("test-key", config);
    
    // Use callAsync to execute with Virtual Thread
    CompletableFuture<String> asyncResult = future.callAsync(failover -> "result");
    
    assertEquals("result", asyncResult.get(5, TimeUnit.SECONDS));
  }

  @Test
  public void testVirtualThreadConcurrency() throws Exception {
    Config config = new Config();
    config.useVirtualThreads = true;
    config.threadsPerKey = 10; // This would be 40 for Virtual Threads due to the 4x multiplier
    
    CooperatingFuture<String> future = new CooperatingFuture<>("test-key", config);
    
    // Simulate a slow operation
    future.callAsync(failover -> {
      try {
        Thread.sleep(1000);
        return "result";
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    });
    
    // Try to cooperate with 30 threads (would exceed normal limit but should work with Virtual Threads)
    AtomicInteger successCount = new AtomicInteger(0);
    CompletableFuture<?>[] futures = new CompletableFuture[30];
    
    for (int i = 0; i < 30; i++) {
      futures[i] = CompletableFuture.runAsync(() -> {
        try {
          String result = future.cooperate(failover -> "unused");
          if ("result".equals(result)) {
            successCount.incrementAndGet();
          }
        } catch (IOException e) {
          // Ignore
        }
      }, executor);
    }
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
    
    // With Virtual Threads, we should have more successful cooperations than the base limit
    assertTrue("Expected more than 10 successful cooperations with Virtual Threads", 
        successCount.get() > 10);
  }

  @Test
  public void testVirtualThreadFailover() throws Exception {
    Config config = new Config();
    config.useVirtualThreads = true;
    config.minorTimeoutSeconds = 1;
    
    CooperatingFuture<String> future = new CooperatingFuture<>("test-key", config);
    
    // This will never complete
    AtomicBoolean leadThreadStarted = new AtomicBoolean(false);
    CompletableFuture.runAsync(() -> {
      try {
        future.call(failover -> {
          leadThreadStarted.set(true);
          // Simulate a stuck thread
          try {
            Thread.sleep(60000);
          } catch (InterruptedException e) {
            // Ignore
          }
          return "never-returned";
        });
      } catch (IOException e) {
        // Ignore
      }
    }, executor);
    
    // Wait for lead thread to start
    while (!leadThreadStarted.get()) {
      Thread.sleep(100);
    }
    
    // This should failover after timeout
    AtomicBoolean failoverCalled = new AtomicBoolean(false);
    String result = future.cooperate(failover -> {
      failoverCalled.set(failover);
      return "failover-result";
    });
    
    assertEquals("failover-result", result);
    assertTrue("Failover flag should be true", failoverCalled.get());
  }

  @Test
  public void testVirtualThreadCooperationLimit() throws Exception {
    Config config = new Config();
    config.useVirtualThreads = false; // Disable Virtual Thread optimization
    config.threadsPerKey = 5;
    
    CooperatingFuture<String> future = new CooperatingFuture<>("test-key", config);
    
    // Start a lead thread that will take some time
    future.callAsync(failover -> {
      try {
        Thread.sleep(1000);
        return "result";
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
    });
    
    // Try to cooperate with more threads than allowed
    AtomicInteger cooperationExceptions = new AtomicInteger(0);
    CompletableFuture<?>[] futures = new CompletableFuture[10];
    
    for (int i = 0; i < 10; i++) {
      futures[i] = CompletableFuture.runAsync(() -> {
        try {
          future.cooperate(failover -> "unused");
        } catch (CooperationException e) {
          cooperationExceptions.incrementAndGet();
        } catch (IOException e) {
          // Ignore
        }
      }, executor);
    }
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
    
    // With standard threads, we should have hit the cooperation limit
    assertTrue("Expected cooperation exceptions with standard threads", 
        cooperationExceptions.get() > 0);
  }

  @Test
  public void testStaggerTimeoutWithVirtualThreads() throws Exception {
    Config config = new Config();
    config.useVirtualThreads = true;
    
    CooperatingFuture<String> future = new CooperatingFuture<>("test-key", config);
    
    Duration initialGap = Duration.ofMillis(100);
    Duration staggered1 = future.staggerTimeout(initialGap);
    Duration staggered2 = future.staggerTimeout(initialGap);
    
    // Second staggered timeout should be greater than the first
    assertTrue("Second staggered timeout should be greater", 
        staggered2.toMillis() > staggered1.toMillis());
  }
}