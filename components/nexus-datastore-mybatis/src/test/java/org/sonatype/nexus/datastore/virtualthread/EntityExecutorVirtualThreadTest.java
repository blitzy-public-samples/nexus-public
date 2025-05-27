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
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FrozenException;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.datastore.api.SerializedAccessException;
import org.sonatype.nexus.datastore.mybatis.EntityExecutor;
import org.sonatype.nexus.datastore.mybatis.FrozenChecker;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@link EntityExecutor} class with Java 21 Virtual Threads.
 * 
 * This test validates that the MyBatis executor wrapper correctly handles concurrent database operations
 * using virtual threads, ensuring proper delegation of JDBC operations, entity ID generation, exception mapping,
 * and transaction handling within the virtual thread context.
 */
@ExtendWith(MockitoExtension.class)
public class EntityExecutorVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  @Mock
  private Executor delegate;

  @Mock
  private FrozenChecker frozenChecker;

  private EntityExecutor underTest;

  @BeforeEach
  public void setup() {
    underTest = new EntityExecutor(delegate, frozenChecker);
  }

  /**
   * Tests that concurrent commit operations work correctly when executed in virtual threads.
   */
  @Test
  public void testConcurrentCommitWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent commit operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            underTest.commit(true);
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exception not expected in this test
            logger.error("Unexpected exception during commit", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations succeeded
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All virtual thread operations should succeed");

      // Verify the delegate was called the expected number of times
      verify(delegate, times(CONCURRENT_THREADS)).commit(true);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that concurrent update operations work correctly when executed in virtual threads.
   */
  @Test
  public void testConcurrentUpdateWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    MappedStatement ms = mock(MappedStatement.class);

    try {
      // Submit multiple concurrent update operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int id = i;
        executor.submit(() -> {
          try {
            // Simulate an update with a different parameter for each thread
            underTest.update(ms, "entity-" + id);
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exception not expected in this test
            logger.error("Unexpected exception during update", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations succeeded
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All virtual thread operations should succeed");

      // Verify the delegate was called the expected number of times
      verify(delegate, times(CONCURRENT_THREADS)).update(any(), any());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that concurrent query operations work correctly when executed in virtual threads.
   */
  @Test
  public void testConcurrentQueryWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent query operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            underTest.query(null, null, null, null);
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exception not expected in this test
            logger.error("Unexpected exception during query", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations succeeded
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All virtual thread operations should succeed");

      // Verify the delegate was called the expected number of times
      verify(delegate, times(CONCURRENT_THREADS)).query(null, null, null, null);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that exception translation works correctly when executed in virtual threads.
   */
  @Test
  public void testExceptionTranslationInVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(3);
    List<Class<? extends Exception>> caughtExceptions = new ArrayList<>();

    // Configure delegate to throw different exceptions
    doThrow(duplicateKeyException(), serializedAccessException(), missingStateException())
        .when(delegate).commit(anyBoolean());

    try {
      // Test DuplicateKeyException translation
      executor.submit(() -> {
        try {
          underTest.commit(true);
        } catch (Exception e) {
          caughtExceptions.add(e.getClass());
        } finally {
          latch.countDown();
        }
      });

      // Test SerializedAccessException translation
      executor.submit(() -> {
        try {
          underTest.commit(false);
        } catch (Exception e) {
          caughtExceptions.add(e.getClass());
        } finally {
          latch.countDown();
        }
      });

      // Test generic SQLException translation
      executor.submit(() -> {
        try {
          underTest.commit(true);
        } catch (Exception e) {
          caughtExceptions.add(e.getClass());
        } finally {
          latch.countDown();
        }
      });

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all expected exception types were caught
      assertEquals(3, caughtExceptions.size(), "Should have caught 3 exceptions");
      assertTrue(caughtExceptions.contains(DuplicateKeyException.class), 
          "Should have caught DuplicateKeyException");
      assertTrue(caughtExceptions.contains(SerializedAccessException.class), 
          "Should have caught SerializedAccessException");
      assertTrue(caughtExceptions.contains(SQLException.class), 
          "Should have caught SQLException");
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that frozen checking works correctly when executed in virtual threads.
   */
  @Test
  public void testFrozenCheckingInVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger frozenExceptionCount = new AtomicInteger(0);
    MappedStatement ms = mock(MappedStatement.class);

    // Configure frozen checker to throw FrozenException
    doThrow(new FrozenException("Frozen")).when(frozenChecker).checkFrozen(ms);

    try {
      // Submit multiple concurrent update operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            underTest.update(ms, null);
          } catch (FrozenException e) {
            frozenExceptionCount.incrementAndGet();
          } catch (Exception e) {
            // Other exceptions not expected
            logger.error("Unexpected exception", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations resulted in FrozenException
      assertEquals(CONCURRENT_THREADS, frozenExceptionCount.get(), 
          "All operations should have thrown FrozenException");

      // Verify the delegate was never called due to frozen check failing
      verify(delegate, never()).update(any(), any());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that transaction operations (commit/rollback) work correctly when executed in virtual threads.
   */
  @Test
  public void testTransactionOperationsInVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS * 2); // commit + rollback for each thread
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent transaction operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Perform a commit operation
            underTest.commit(true);
            // Perform a rollback operation
            underTest.rollback(false);
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exception not expected in this test
            logger.error("Unexpected exception during transaction operations", e);
          } finally {
            latch.countDown();
            latch.countDown(); // Count down twice for commit and rollback
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations succeeded
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All virtual thread transaction operations should succeed");

      // Verify the delegate was called the expected number of times
      verify(delegate, times(CONCURRENT_THREADS)).commit(true);
      verify(delegate, times(CONCURRENT_THREADS)).rollback(false);
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that flushStatements operations work correctly when executed in virtual threads.
   */
  @Test
  public void testFlushStatementsInVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);

    try {
      // Submit multiple concurrent flushStatements operations using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            underTest.flushStatements();
            successCount.incrementAndGet();
          } catch (Exception e) {
            // Exception not expected in this test
            logger.error("Unexpected exception during flushStatements", e);
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");

      // Verify all operations succeeded
      assertEquals(CONCURRENT_THREADS, successCount.get(), 
          "All virtual thread flushStatements operations should succeed");

      // Verify the delegate was called the expected number of times
      verify(delegate, times(CONCURRENT_THREADS)).flushStatements();
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Creates a SQLException with the SQL state for duplicate key violations.
   */
  private static SQLException duplicateKeyException() {
    return new SQLException("Duplicate Key", DuplicateKeyException.SQL_STATE);
  }

  /**
   * Creates a SQLException with the SQL state for serialized access violations.
   */
  private static SQLException serializedAccessException() {
    return new SQLException("Isolation", SerializedAccessException.SQL_STATE);
  }

  /**
   * Creates a generic SQLException without a specific SQL state.
   */
  private static SQLException missingStateException() {
    return new SQLException("Some hikari error");
  }
}