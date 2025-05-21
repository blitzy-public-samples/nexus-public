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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class DatabaseStatusDelayedExecutorTest
    extends TestSupport
{

  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;

  @BeforeEach
  public void setup() throws Exception {
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES);
    statusDelayedExecutor.start();
  }

  @Test
  public void ensureThatTaskEventuallyRuns() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    Future<String> result = statusDelayedExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  @Test
  public void noWritableDelaysTask() {
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

    Assertions.assertFalse(result.isDone());

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    Assertions.assertEquals(5, callCount.get());
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void ensureThatTaskEventuallyRunsWithVirtualThreads() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor with virtual threads
    DatabaseStatusDelayedExecutor virtualThreadExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, virtualThreadFactory);
    virtualThreadExecutor.start();
    
    try {
      Future<String> result = virtualThreadExecutor.submit(() -> "Done");

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());

      verify(freezeService, times(MAX_RETRIES * 2)).checkWritable(anyString());
    } finally {
      virtualThreadExecutor.shutdown();
    }
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void noWritableDelaysTaskWithVirtualThreads() {
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor with virtual threads
    DatabaseStatusDelayedExecutor virtualThreadExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, virtualThreadFactory);
    virtualThreadExecutor.start();
    
    try {
      Future<String> result = virtualThreadExecutor.submit(() -> "Done");

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(callCount::get, greaterThanOrEqualTo(1));

      Assertions.assertFalse(result.isDone());

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());

      Assertions.assertEquals(5, callCount.get());
    } finally {
      virtualThreadExecutor.shutdown();
    }
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void concurrentTasksWithVirtualThreads() {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor with virtual threads
    DatabaseStatusDelayedExecutor virtualThreadExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 5, SLEEP_INTERVAL_MS, MAX_RETRIES, virtualThreadFactory);
    virtualThreadExecutor.start();
    
    try {
      // Submit multiple tasks concurrently
      Future<String> result1 = virtualThreadExecutor.submit(() -> "Task1");
      Future<String> result2 = virtualThreadExecutor.submit(() -> "Task2");
      Future<String> result3 = virtualThreadExecutor.submit(() -> "Task3");
      
      // Wait for all tasks to complete
      await()
          .atMost(5 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result1.isDone() && result2.isDone() && result3.isDone());
      
      // Verify all tasks completed successfully
      Assertions.assertTrue(result1.isDone());
      Assertions.assertTrue(result2.isDone());
      Assertions.assertTrue(result3.isDone());
    } finally {
      virtualThreadExecutor.shutdown();
    }
  }
}