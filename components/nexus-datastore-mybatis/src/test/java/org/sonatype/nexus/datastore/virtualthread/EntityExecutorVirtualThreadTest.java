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
package org.sonatype.nexus.datastore.virtualthread;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FrozenException;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.datastore.api.SerializedAccessException;
import org.sonatype.nexus.datastore.mybatis.EntityExecutor;
import org.sonatype.nexus.datastore.mybatis.FrozenChecker;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link EntityExecutor} class with Java 21 Virtual Threads, validating that the MyBatis executor wrapper
 * correctly handles concurrent database operations using virtual threads.
 */
public class EntityExecutorVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(EntityExecutorVirtualThreadTest.class);

  private static final int VIRTUAL_THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private Executor delegate;

  @Mock
  private FrozenChecker frozenChecker;

  private EntityExecutor underTest;

  @Before
  public void setup() {
    underTest = new EntityExecutor(delegate, frozenChecker);
  }

  /**
   * Tests that commit operations work correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentCommit() throws Exception {
    log.info("Starting concurrent commit test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executorService.submit(() -> {
          try {
            underTest.commit(true);
          }
          catch (Exception e) {
            log.error("Error in virtual thread commit operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All commit operations should complete within timeout", completed, is(true));
      assertThat("All commit operations should succeed", success.get(), is(true));
      verify(delegate, times(VIRTUAL_THREAD_COUNT)).commit(true);
    }
  }

  /**
   * Tests that rollback operations work correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentRollback() throws Exception {
    log.info("Starting concurrent rollback test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executorService.submit(() -> {
          try {
            underTest.rollback(true);
          }
          catch (Exception e) {
            log.error("Error in virtual thread rollback operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All rollback operations should complete within timeout", completed, is(true));
      assertThat("All rollback operations should succeed", success.get(), is(true));
      verify(delegate, times(VIRTUAL_THREAD_COUNT)).rollback(true);
    }
  }

  /**
   * Tests that update operations work correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentUpdate() throws Exception {
    log.info("Starting concurrent update test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);
    MappedStatement ms = mock(MappedStatement.class);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executorService.submit(() -> {
          try {
            // Use thread ID as a parameter to simulate different update operations
            underTest.update(ms, threadId);
          }
          catch (Exception e) {
            log.error("Error in virtual thread update operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All update operations should complete within timeout", completed, is(true));
      assertThat("All update operations should succeed", success.get(), is(true));
      
      // Verify that update was called for each thread with the correct parameter
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        verify(delegate).update(ms, i);
      }
    }
  }

  /**
   * Tests that query operations work correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentQuery() throws Exception {
    log.info("Starting concurrent query test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executorService.submit(() -> {
          try {
            // Use thread ID to create unique query parameters
            underTest.query(null, threadId, null, null);
          }
          catch (Exception e) {
            log.error("Error in virtual thread query operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All query operations should complete within timeout", completed, is(true));
      assertThat("All query operations should succeed", success.get(), is(true));
      
      // Verify that query was called for each thread with the correct parameter
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        verify(delegate).query(null, i, null, null);
      }
    }
  }

  /**
   * Tests that error translation works correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testErrorTranslationInVirtualThreads() throws Exception {
    log.info("Starting error translation test with virtual threads");
    CountDownLatch latch = new CountDownLatch(3); // One for each error type
    List<Exception> caughtExceptions = new ArrayList<>();
    MappedStatement ms = mock(MappedStatement.class);

    // Configure delegate to throw different exceptions for different parameter values
    when(delegate.update(ms, 0)).thenThrow(duplicateKeyException());
    when(delegate.update(ms, 1)).thenThrow(serializedAccessException());
    when(delegate.update(ms, 2)).thenThrow(missingStateException());

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      // Test duplicate key exception
      executorService.submit(() -> {
        try {
          underTest.update(ms, 0);
        }
        catch (Exception e) {
          synchronized (caughtExceptions) {
            caughtExceptions.add(e);
          }
        }
        finally {
          latch.countDown();
        }
      });

      // Test serialized access exception
      executorService.submit(() -> {
        try {
          underTest.update(ms, 1);
        }
        catch (Exception e) {
          synchronized (caughtExceptions) {
            caughtExceptions.add(e);
          }
        }
        finally {
          latch.countDown();
        }
      });

      // Test generic SQL exception
      executorService.submit(() -> {
        try {
          underTest.update(ms, 2);
        }
        catch (Exception e) {
          synchronized (caughtExceptions) {
            caughtExceptions.add(e);
          }
        }
        finally {
          latch.countDown();
        }
      });

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All error test operations should complete within timeout", completed, is(true));
      
      // Verify that we got the expected exceptions with proper translation
      assertThat("Should have caught 3 exceptions", caughtExceptions.size(), is(3));
      
      boolean foundDuplicateKey = false;
      boolean foundSerializedAccess = false;
      boolean foundSqlException = false;
      
      for (Exception e : caughtExceptions) {
        if (e instanceof DuplicateKeyException) {
          foundDuplicateKey = true;
        }
        else if (e instanceof SerializedAccessException) {
          foundSerializedAccess = true;
        }
        else if (e instanceof SQLException) {
          foundSqlException = true;
        }
      }
      
      assertThat("Should have translated to DuplicateKeyException", foundDuplicateKey, is(true));
      assertThat("Should have translated to SerializedAccessException", foundSerializedAccess, is(true));
      assertThat("Should have preserved SQLException", foundSqlException, is(true));
    }
  }

  /**
   * Tests that frozen checks work correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testFrozenCheckInVirtualThreads() throws Exception {
    log.info("Starting frozen check test with virtual threads");
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicInteger frozenExceptionCount = new AtomicInteger(0);
    MappedStatement ms = mock(MappedStatement.class);
    
    // Configure frozen checker to throw exception for even thread IDs
    doThrow(new FrozenException("Frozen")).when(frozenChecker).checkFrozen(ms);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executorService.submit(() -> {
          try {
            underTest.update(ms, null);
          }
          catch (FrozenException e) {
            frozenExceptionCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Unexpected error in frozen check test: {}", e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All frozen check operations should complete within timeout", completed, is(true));
      assertThat("All operations should have thrown FrozenException", 
          frozenExceptionCount.get(), is(VIRTUAL_THREAD_COUNT));
      verify(delegate, never()).update(ms, null);
    }
  }

  /**
   * Tests that flushStatements works correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentFlushStatements() throws Exception {
    log.info("Starting concurrent flushStatements test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executorService.submit(() -> {
          try {
            underTest.flushStatements();
          }
          catch (Exception e) {
            log.error("Error in virtual thread flushStatements operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All flushStatements operations should complete within timeout", completed, is(true));
      assertThat("All flushStatements operations should succeed", success.get(), is(true));
      verify(delegate, times(VIRTUAL_THREAD_COUNT)).flushStatements();
    }
  }

  /**
   * Tests that queryCursor works correctly in a concurrent virtual thread environment.
   */
  @Test
  public void testConcurrentQueryCursor() throws Exception {
    log.info("Starting concurrent queryCursor test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executorService.submit(() -> {
          try {
            // Use thread ID to create unique query parameters
            underTest.queryCursor(null, threadId, null);
          }
          catch (Exception e) {
            log.error("Error in virtual thread queryCursor operation: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All queryCursor operations should complete within timeout", completed, is(true));
      assertThat("All queryCursor operations should succeed", success.get(), is(true));
      
      // Verify that queryCursor was called for each thread with the correct parameter
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        verify(delegate).queryCursor(null, i, null);
      }
    }
  }

  /**
   * Tests a mixed workload of different EntityExecutor operations in a concurrent virtual thread environment.
   */
  @Test
  public void testMixedWorkload() throws Exception {
    log.info("Starting mixed workload test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    AtomicBoolean success = new AtomicBoolean(true);
    MappedStatement ms = mock(MappedStatement.class);

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executorService.submit(() -> {
          try {
            // Each thread performs a different operation based on its ID
            switch (threadId % 5) {
              case 0:
                underTest.update(ms, threadId);
                break;
              case 1:
                underTest.query(null, threadId, null, null);
                break;
              case 2:
                underTest.queryCursor(null, threadId, null);
                break;
              case 3:
                underTest.commit(true);
                break;
              case 4:
                underTest.rollback(true);
                break;
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread mixed workload: {}", e.getMessage(), e);
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All mixed workload operations should complete within timeout", completed, is(true));
      assertThat("All mixed workload operations should succeed", success.get(), is(true));
      
      // Verify that the appropriate number of each operation was called
      int expectedCount = VIRTUAL_THREAD_COUNT / 5;
      int remainder = VIRTUAL_THREAD_COUNT % 5;
      
      verify(delegate, times(expectedCount + (remainder > 0 ? 1 : 0))).update(ms, 0);
      verify(delegate, times(expectedCount + (remainder > 1 ? 1 : 0))).query(null, 1, null, null);
      verify(delegate, times(expectedCount + (remainder > 2 ? 1 : 0))).queryCursor(null, 2, null);
      verify(delegate, times(expectedCount + (remainder > 3 ? 1 : 0))).commit(true);
      verify(delegate, times(expectedCount + (remainder > 4 ? 1 : 0))).rollback(true);
    }
  }

  private static SQLException duplicateKeyException() {
    return new SQLException("Duplicate Key", DuplicateKeyException.SQL_STATE);
  }

  private static SQLException serializedAccessException() {
    return new SQLException("Isolation", SerializedAccessException.SQL_STATE);
  }

  private static SQLException missingStateException() {
    return new SQLException("Some hikari error");
  }
}