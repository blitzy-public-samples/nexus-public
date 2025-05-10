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
package org.sonatype.nexus.thread;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DatabaseStatusDelayedExecutorTest
    extends TestSupport
{

  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;

  @BeforeEach
  void setup() throws Exception {
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, false);
    statusDelayedExecutor.start();
  }

  @Test
  void ensureThatTaskEventuallyRuns() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    Future<String> result = statusDelayedExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  @Test
  void noWritableDelaysTask() {
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    Future<String> result = statusDelayedExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(callCount::get, greaterThanOrEqualTo(1));

    assertThat(result.isDone(), is(false));

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    assertThat(callCount.get(), is(5));
  }

  @Test
  void testVirtualThreadExecution() {
    // Create a new executor with virtual threads enabled
    DatabaseStatusDelayedExecutor virtualThreadExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, true);
    virtualThreadExecutor.start();
    
    try {
      // Configure mock to succeed immediately
      doAnswer(invocation -> null).when(freezeService).checkWritable(anyString());
      
      // Submit a task that verifies it's running on a virtual thread
      Future<Boolean> result = virtualThreadExecutor.submit(() -> Thread.currentThread().isVirtual());
      
      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());
      
      // Verify the task ran on a virtual thread
      assertThat(result.isDone(), is(true));
      assertThat(result.get(), is(true));
      
      // Verify the freeze service was called
      verify(freezeService).checkWritable(anyString());
    } catch (Exception e) {
      throw new RuntimeException("Test failed", e);
    } finally {
      virtualThreadExecutor.stop();
    }
  }
  
  @Test
  void testVirtualThreadRetryLogic() {
    // Create a new executor with virtual threads enabled
    DatabaseStatusDelayedExecutor virtualThreadExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, true);
    virtualThreadExecutor.start();
    
    try {
      final AtomicInteger callCount = new AtomicInteger(0);
      doAnswer(invocation -> {
        if (callCount.incrementAndGet() <= 3) {
          throw new NotWritableException("Database not writable");
        }
        return null;
      }).when(freezeService).checkWritable(anyString());
      
      // Submit a task that should be delayed until the database is writable
      Future<String> result = virtualThreadExecutor.submit(() -> "Done with virtual thread");
      
      // Verify the task is delayed
      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(callCount::get, greaterThanOrEqualTo(1));
      
      assertThat(result.isDone(), is(false));
      
      // Wait for the task to complete after retries
      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());
      
      // Verify the retry count and result
      assertThat(callCount.get(), is(4)); // 3 failures + 1 success
      assertThat(result.get(), is("Done with virtual thread"));
    } catch (Exception e) {
      throw new RuntimeException("Test failed", e);
    } finally {
      virtualThreadExecutor.stop();
    }
  }
}