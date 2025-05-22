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
package virtualthread;

import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.sonatype.nexus.common.app.FrozenException;
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.datastore.api.SerializedAccessException;
import org.sonatype.nexus.datastore.mybatis.EntityExecutor;
import org.sonatype.nexus.datastore.mybatis.FrozenChecker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link EntityExecutor} with Java 21 Virtual Threads to ensure delegation, exception translation,
 * and frozen state enforcement work correctly in the new threading model.
 */
@ExtendWith(MockitoExtension.class)
class EntityExecutorVirtualThreadTest
{
  @Mock
  private Executor delegate;

  @Mock
  private FrozenChecker frozenChecker;

  private EntityExecutor underTest;

  @BeforeEach
  void setup() {
    underTest = new EntityExecutor(delegate, frozenChecker);
  }

  @Test
  void testCommitWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        try {
          underTest.commit(true);
          verify(delegate).commit(true);
        }
        catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }).get(5, TimeUnit.SECONDS);
      
      // Test exception translation on virtual threads
      doThrow(duplicateKeyException()).when(delegate).commit(true);
      assertThrows(DuplicateKeyException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.commit(true);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      doThrow(serializedAccessException()).when(delegate).commit(true);
      assertThrows(SerializedAccessException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.commit(true);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      doThrow(missingStateException()).when(delegate).commit(true);
      assertThrows(SQLException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.commit(true);
          }
          catch (SQLException e) {
            throw e;
          }
        }).get(5, TimeUnit.SECONDS);
      });
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testFlushStatementsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        try {
          underTest.flushStatements();
          verify(delegate).flushStatements();
        }
        catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }).get(5, TimeUnit.SECONDS);
      
      // Test exception translation on virtual threads
      when(delegate.flushStatements()).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
      
      assertThrows(DuplicateKeyException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.flushStatements();
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SerializedAccessException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.flushStatements();
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SQLException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.flushStatements();
          }
          catch (SQLException e) {
            throw e;
          }
        }).get(5, TimeUnit.SECONDS);
      });
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testQueryWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        try {
          underTest.query(null, null, null, null);
          verify(delegate).query(null, null, null, null);
        }
        catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }).get(5, TimeUnit.SECONDS);
      
      // Test exception translation on virtual threads
      when(delegate.query(null, null, null, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
      
      assertThrows(DuplicateKeyException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.query(null, null, null, null);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SerializedAccessException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.query(null, null, null, null);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SQLException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.query(null, null, null, null);
          }
          catch (SQLException e) {
            throw e;
          }
        }).get(5, TimeUnit.SECONDS);
      });
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testUpdateWithVirtualThreads() throws Exception {
    MappedStatement ms = mock(MappedStatement.class);
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      executor.submit(() -> {
        try {
          underTest.update(ms, null);
          verify(delegate).update(ms, null);
        }
        catch (SQLException e) {
          throw new RuntimeException(e);
        }
      }).get(5, TimeUnit.SECONDS);
      
      // Test exception translation on virtual threads
      when(delegate.update(ms, null)).thenThrow(duplicateKeyException(), serializedAccessException(), missingStateException());
      
      assertThrows(DuplicateKeyException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.update(ms, null);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SerializedAccessException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.update(ms, null);
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      assertThrows(SQLException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.update(ms, null);
          }
          catch (SQLException e) {
            throw e;
          }
        }).get(5, TimeUnit.SECONDS);
      });
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testUpdateFrozenWithVirtualThreads() throws Exception {
    MappedStatement ms = mock(MappedStatement.class);
    doThrow(new FrozenException("Frozen")).when(frozenChecker).checkFrozen(ms);
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      assertThrows(FrozenException.class, () -> {
        executor.submit(() -> {
          try {
            underTest.update(ms, null);
          }
          catch (Exception e) {
            throw e;
          }
        }).get(5, TimeUnit.SECONDS);
      });
      
      verify(delegate, never()).update(ms, null);
    }
    finally {
      executor.shutdown();
    }
  }

  @Test
  void testConcurrentOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            underTest.flushStatements();
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
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      verify(delegate, org.mockito.Mockito.times(taskCount)).flushStatements();
    } 
    finally {
      executor.shutdown();
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