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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
  
  @Captor
  ArgumentCaptor<String> messageCaptor;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;
  DatabaseStatusDelayedExecutor virtualThreadExecutor;

  @BeforeEach
  public void setup() throws Exception {
    // Platform thread executor
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, false);
    statusDelayedExecutor.start();
    
    // Virtual thread executor
    virtualThreadExecutor = new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, true);
    virtualThreadExecutor.start();
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

    assertThat(result.isDone(), is(false));

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    assertThat(callCount.get(), is(5));
  }
  
  @Test
  public void virtualThreadModeIsCorrectlyReported() {
    // Verify platform thread executor reports false
    assertFalse(statusDelayedExecutor.isUsingVirtualThreads());
    
    // Verify virtual thread executor reports true
    assertTrue(virtualThreadExecutor.isUsingVirtualThreads());
  }
  
  @Test
  public void virtualThreadsExecuteTasksCorrectly() {
    final AtomicBoolean taskExecuted = new AtomicBoolean(false);
    
    // Submit a task to the virtual thread executor
    Future<String> result = virtualThreadExecutor.submit(() -> {
      taskExecuted.set(true);
      return "Virtual Thread Task Completed";
    });
    
    // Wait for the task to complete
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(SLEEP_INTERVAL_MS * 2, MILLISECONDS)
        .until(taskExecuted::get);
    
    // Verify the task was executed
    assertTrue(taskExecuted.get());
    assertTrue(result.isDone());
  }
  
  @Test
  public void virtualThreadsHandleRetryLogicCorrectly() {
    final AtomicInteger callCount = new AtomicInteger(0);
    
    // Configure the freeze service to throw NotWritableException for the first 3 calls
    doAnswer(invocation -> {
      int count = callCount.incrementAndGet();
      if (count <= 3) {
        throw new NotWritableException("Database not writable - attempt " + count);
      }
      return null;
    }).when(freezeService).checkWritable(messageCaptor.capture());
    
    // Submit a task to the virtual thread executor
    Future<String> result = virtualThreadExecutor.submit(() -> "Virtual Thread Task With Retries");
    
    // Wait for the task to complete
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(SLEEP_INTERVAL_MS * 10, MILLISECONDS)
        .until(() -> result.isDone());
    
    // Verify the retry logic was executed correctly
    assertThat(callCount.get(), is(4)); // 3 failures + 1 success
    verify(freezeService, times(4)).checkWritable(anyString());
    
    // Verify the message passed to checkWritable
    assertThat(messageCaptor.getValue(), containsString("Task needs writable database"));
  }
  
  @Test
  public void customExecutorServiceCanBeUsed() {
    // Create a custom executor service using virtual threads
    ExecutorService customExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a new DatabaseStatusDelayedExecutor with the custom executor
    DatabaseStatusDelayedExecutor customDelayedExecutor = 
        new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES, true);
    customDelayedExecutor.setExecutor(customExecutor);
    
    // Verify the executor is using virtual threads
    assertTrue(customDelayedExecutor.isUsingVirtualThreads());
    
    // Submit a task and verify it executes correctly
    Future<String> result = customDelayedExecutor.submit(() -> "Custom Executor Task");
    
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(SLEEP_INTERVAL_MS * 2, MILLISECONDS)
        .until(() -> result.isDone());
    
    // Clean up
    customDelayedExecutor.shutdown();
  }
}