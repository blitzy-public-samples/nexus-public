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
package org.sonatype.nexus.datastore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javax.inject.Provider;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.transaction.TransactionException;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating Jakarta Transaction API integration with Virtual Threads in the Nexus datastore.
 * This class verifies that Jakarta Transaction annotations and APIs function correctly when used with Virtual Threads,
 * ensuring proper transaction demarcation, resource management, and exception handling.
 */
public class DataStoreJakartaTransactionVirtualThreadTest
    extends TestSupport
{
  @Mock
  private DataStoreDescriptor descriptor;

  @Mock
  private Provider<DataStore<?>> prototype;

  @Mock
  private DataStoreConfigurationManager configurationManager;

  @Mock
  private EventManager eventManager;

  private TestDataStore dataStore;

  private DataStoreManagerImpl dataStoreManager;

  @Before
  public void setup() throws Exception {
    when(descriptor.isEnabled()).thenReturn(true);
    when(prototype.get()).thenAnswer(invocation -> {
      dataStore = new TestDataStore();
      return dataStore;
    });

    dataStoreManager = new DataStoreManagerImpl(
        true,
        eventManager,
        ImmutableMap.of("test", descriptor),
        ImmutableMap.of("test", prototype),
        configurationManager,
        () -> mock(DataStoreUsageChecker.class),
        mock(DataStoreRestorer.class),
        null);

    dataStoreManager.start();

    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName("testStore");
    config.setType("test");
    config.setSource("local");
    config.setAttributes(ImmutableMap.of());

    dataStoreManager.create(config);
  }

  @After
  public void tearDown() throws Exception {
    UnitOfWork.end();
    dataStoreManager.stop();
  }

  /**
   * Test class that extends DataStoreSupport for testing transaction behavior.
   */
  static class TestDataStore
      extends DataStoreSupport<DataSession<?>>
  {
    private boolean throwExceptionOnCommit = false;
    private boolean throwExceptionOnRollback = false;

    @Override
    public void register(final Class<? extends DataAccess> accessType) {
      // no-op
    }

    @Override
    public void unregister(final Class<? extends DataAccess> accessType) {
      // no-op
    }

    @Override
    public DataSession<?> openSession() {
      DataSession<?> session = mock(DataSession.class);
      when(session.getTransaction()).thenReturn(mock(jakarta.transaction.Transaction.class));
      return session;
    }

    @Override
    public Connection openConnection() throws SQLException {
      Connection connection = mock(Connection.class);
      if (throwExceptionOnCommit) {
        doThrow(new SQLException("Simulated commit failure")).when(connection).commit();
      }
      if (throwExceptionOnRollback) {
        doThrow(new SQLException("Simulated rollback failure")).when(connection).rollback();
      }
      return connection;
    }

    @Override
    protected void doStart(final String storeName, final Map<String, String> attributes) {
      // no-op
    }

    @Override
    public void freeze() {
      // no-op
    }

    @Override
    public void unfreeze() {
      // no-op
    }

    @Override
    public boolean isFrozen() {
      return false;
    }

    @Override
    public void backup(final String location) {
      // no-op
    }

    public void setThrowExceptionOnCommit(boolean throwExceptionOnCommit) {
      this.throwExceptionOnCommit = throwExceptionOnCommit;
    }

    public void setThrowExceptionOnRollback(boolean throwExceptionOnRollback) {
      this.throwExceptionOnRollback = throwExceptionOnRollback;
    }
  }

  /**
   * Tests that transactions can be started and committed successfully using Virtual Threads.
   */
  @Test
  public void testTransactionCommitWithVirtualThread() throws Exception {
    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          UnitOfWork.begin(dataStore);
          Connection connection = UnitOfWork.currentConnection();
          UnitOfWork.end();
          verify(connection).commit();
          verify(connection, never()).rollback();
          return true;
        }
        catch (Exception e) {
          fail("Transaction should commit successfully: " + e.getMessage());
          return false;
        }
      }, executor);

      assertThat(future.join(), is(true));
    }
  }

  /**
   * Tests that transactions are properly rolled back when an exception occurs using Virtual Threads.
   */
  @Test
  public void testTransactionRollbackWithVirtualThread() throws Exception {
    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(dataStore);
        Connection connection = UnitOfWork.currentConnection();
        try {
          throw new RuntimeException("Simulated error");
        }
        catch (RuntimeException e) {
          try {
            UnitOfWork.end(e);
            verify(connection, never()).commit();
            verify(connection).rollback();
            return true;
          }
          catch (Exception ex) {
            fail("Transaction should rollback successfully: " + ex.getMessage());
            return false;
          }
        }
      }, executor);

      assertThat(future.join(), is(true));
    }
  }

  /**
   * Tests that transaction context is properly maintained across multiple Virtual Threads.
   */
  @Test
  public void testTransactionContextAcrossVirtualThreads() throws Exception {
    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      // Start transaction in one virtual thread
      CompletableFuture<Connection> startFuture = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(dataStore);
        return UnitOfWork.currentConnection();
      }, executor);

      Connection connection = startFuture.join();

      // Use transaction in another virtual thread
      CompletableFuture<Boolean> useFuture = CompletableFuture.supplyAsync(() -> {
        try {
          // This should throw an exception because the transaction context is not propagated
          // to the new virtual thread automatically
          UnitOfWork.currentConnection();
          fail("Should not be able to access transaction from different virtual thread");
          return false;
        }
        catch (IllegalStateException e) {
          // Expected exception because transaction context is thread-local
          return true;
        }
      }, executor);

      assertThat(useFuture.join(), is(true));

      // End transaction in original thread
      CompletableFuture<Boolean> endFuture = CompletableFuture.supplyAsync(() -> {
        try {
          // This should throw an exception because the transaction context is not available
          // in this thread either
          UnitOfWork.end();
          fail("Should not be able to end transaction from different virtual thread");
          return false;
        }
        catch (IllegalStateException e) {
          // Expected exception because transaction context is thread-local
          return true;
        }
      }, executor);

      assertThat(endFuture.join(), is(true));

      // Clean up the transaction in the original thread context
      UnitOfWork.end();
    }
  }

  /**
   * Tests that transaction commit failures are properly handled with Virtual Threads.
   */
  @Test
  public void testTransactionCommitFailureWithVirtualThread() throws Exception {
    dataStore.setThrowExceptionOnCommit(true);

    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(dataStore);
        try {
          UnitOfWork.end();
          fail("Should throw TransactionException on commit failure");
          return false;
        }
        catch (TransactionException e) {
          // Expected exception
          return e.getCause() instanceof SQLException;
        }
      }, executor);

      assertThat(future.join(), is(true));
    }
  }

  /**
   * Tests that transaction rollback failures are properly handled with Virtual Threads.
   */
  @Test
  public void testTransactionRollbackFailureWithVirtualThread() throws Exception {
    dataStore.setThrowExceptionOnRollback(true);

    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(dataStore);
        try {
          UnitOfWork.end(new RuntimeException("Trigger rollback"));
          fail("Should throw TransactionException on rollback failure");
          return false;
        }
        catch (TransactionException e) {
          // Expected exception
          return e.getCause() instanceof SQLException;
        }
      }, executor);

      assertThat(future.join(), is(true));
    }
  }

  /**
   * Tests concurrent transactions with multiple Virtual Threads.
   */
  @Test
  public void testConcurrentTransactionsWithVirtualThreads() throws Exception {
    final int threadCount = 100;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);

    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      // Start multiple concurrent transactions
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        CompletableFuture.runAsync(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Each thread gets its own transaction
            UnitOfWork.begin(dataStore);
            Connection connection = UnitOfWork.currentConnection();
            
            // Simulate some work
            Thread.sleep(10);
            
            UnitOfWork.end();
            verify(connection).commit();
            verify(connection, never()).rollback();
          }
          catch (Exception e) {
            fail("Thread " + threadId + " failed: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        }, executor);
      }

      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await();
    }
  }

  /**
   * Tests nested transaction attempts with Virtual Threads.
   */
  @Test
  public void testNestedTransactionsWithVirtualThread() throws Exception {
    try (ExecutorService executor = newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(dataStore);
        try {
          // Attempt to start a nested transaction
          UnitOfWork.begin(dataStore);
          fail("Should throw IllegalStateException for nested transaction");
          return false;
        }
        catch (IllegalStateException e) {
          // Expected exception for nested transaction attempt
          try {
            UnitOfWork.end();
            return true;
          }
          catch (Exception ex) {
            fail("Failed to end transaction: " + ex.getMessage());
            return false;
          }
        }
      }, executor);

      assertThat(future.join(), is(true));
    }
  }
}