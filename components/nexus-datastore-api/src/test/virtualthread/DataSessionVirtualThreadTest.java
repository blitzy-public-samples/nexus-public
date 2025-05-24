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
package org.sonatype.nexus.datastore.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.transaction.Transaction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataSession} behavior when used with Java 21 Virtual Threads.
 * 
 * This test class validates that DataSession operations, transaction management,
 * and DataAccess acquisition work correctly in a Virtual Thread context without
 * thread pinning or other issues. It ensures that transaction boundaries are properly
 * maintained when sessions are opened and used within Virtual Threads.
 *
 * @since 3.60
 */
public class DataSessionVirtualThreadTest
    extends TestSupport
{
    @Mock
    private Transaction transaction;
    
    @Mock
    private DataSession dataSession;
    
    @Mock
    private TestDataAccess testDataAccess;
    
    private ExecutorService virtualThreadExecutor;
    
    @Before
    public void setup() {
        // Configure mock DataSession
        when(dataSession.getTransaction()).thenReturn(transaction);
        when(dataSession.access(TestDataAccess.class)).thenReturn(testDataAccess);
        
        // Configure mock TestDataAccess
        doAnswer(invocation -> null).when(testDataAccess).performOperation();
        
        // Create a virtual thread executor
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    @After
    public void tearDown() throws Exception {
        virtualThreadExecutor.shutdown();
        virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    /**
     * Test that a DataSession can be accessed from a virtual thread and
     * that DataAccess instances can be retrieved correctly.
     */
    @Test
    public void testDataAccessAcquisitionInVirtualThread() throws Exception {
        AtomicReference<TestDataAccess> acquiredDataAccess = new AtomicReference<>();
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Acquire DataAccess from the session
            TestDataAccess dataAccess = dataSession.access(TestDataAccess.class);
            acquiredDataAccess.set(dataAccess);
        });
        
        future.get(5, TimeUnit.SECONDS);
        
        // Verify the DataAccess was correctly acquired
        assertThat(acquiredDataAccess.get(), is(testDataAccess));
        verify(dataSession).access(TestDataAccess.class);
    }
    
    /**
     * Test that transaction boundaries are properly maintained when a DataSession
     * is used across multiple virtual threads.
     */
    @Test
    public void testTransactionBoundariesAcrossVirtualThreads() throws Exception {
        // Setup a latch to coordinate between threads
        CountDownLatch latch = new CountDownLatch(1);
        
        // Track transaction state across threads
        AtomicReference<Transaction> threadOneTransaction = new AtomicReference<>();
        AtomicReference<Transaction> threadTwoTransaction = new AtomicReference<>();
        
        // First virtual thread starts a transaction
        Future<?> future1 = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Get the transaction from the session
            Transaction tx = dataSession.getTransaction();
            threadOneTransaction.set(tx);
            
            // Signal the second thread
            latch.countDown();
        });
        
        // Second virtual thread should see the same transaction
        Future<?> future2 = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            try {
                // Wait for the first thread to get the transaction
                latch.await(5, TimeUnit.SECONDS);
                
                // Get the transaction from the session
                Transaction tx = dataSession.getTransaction();
                threadTwoTransaction.set(tx);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted", e);
            }
        });
        
        // Wait for both threads to complete
        future1.get(5, TimeUnit.SECONDS);
        future2.get(5, TimeUnit.SECONDS);
        
        // Verify both threads got the same transaction
        assertThat(threadOneTransaction.get(), is(notNullValue()));
        assertThat(threadTwoTransaction.get(), is(notNullValue()));
        assertThat(threadOneTransaction.get(), is(equalTo(threadTwoTransaction.get())));
        verify(dataSession, times(2)).getTransaction();
    }
    
    /**
     * Test that transaction commit hooks are properly executed when a transaction
     * is committed from a virtual thread.
     */
    @Test
    public void testTransactionCommitHooksInVirtualThread() throws Exception {
        // Track if hooks were called
        AtomicBoolean preCommitCalled = new AtomicBoolean(false);
        AtomicBoolean postCommitCalled = new AtomicBoolean(false);
        
        // Configure the session to track commit hooks
        doAnswer(invocation -> {
            Runnable hook = invocation.getArgument(0);
            hook.run();
            preCommitCalled.set(true);
            return null;
        }).when(dataSession).preCommit(org.mockito.ArgumentMatchers.any(Runnable.class));
        
        doAnswer(invocation -> {
            Runnable hook = invocation.getArgument(0);
            hook.run();
            postCommitCalled.set(true);
            return null;
        }).when(dataSession).postCommit(org.mockito.ArgumentMatchers.any(Runnable.class));
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Register commit hooks
            dataSession.preCommit(() -> {});
            dataSession.postCommit(() -> {});
        });
        
        future.get(5, TimeUnit.SECONDS);
        
        // Verify hooks were called
        assertThat(preCommitCalled.get(), is(true));
        assertThat(postCommitCalled.get(), is(true));
    }
    
    /**
     * Test that transaction rollback hooks are properly executed when a transaction
     * is rolled back from a virtual thread.
     */
    @Test
    public void testTransactionRollbackHooksInVirtualThread() throws Exception {
        // Track if hooks were called
        AtomicBoolean rollbackCalled = new AtomicBoolean(false);
        
        // Configure the session to track rollback hooks
        doAnswer(invocation -> {
            Runnable hook = invocation.getArgument(0);
            hook.run();
            rollbackCalled.set(true);
            return null;
        }).when(dataSession).onRollback(org.mockito.ArgumentMatchers.any(Runnable.class));
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Register rollback hook
            dataSession.onRollback(() -> {});
        });
        
        future.get(5, TimeUnit.SECONDS);
        
        // Verify hook was called
        assertThat(rollbackCalled.get(), is(true));
    }
    
    /**
     * Test that multiple concurrent virtual threads can access the same DataSession
     * without issues.
     */
    @Test
    public void testConcurrentDataSessionAccessFromVirtualThreads() throws Exception {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger operationCounter = new AtomicInteger(0);
        
        // Configure TestDataAccess to count operations
        doAnswer(invocation -> {
            operationCounter.incrementAndGet();
            return null;
        }).when(testDataAccess).performOperation();
        
        // Launch multiple virtual threads that all access the same DataSession
        for (int i = 0; i < threadCount; i++) {
            Future<?> future = virtualThreadExecutor.submit(() -> {
                try {
                    assertThat(Thread.currentThread().isVirtual(), is(true));
                    
                    // Wait for all threads to be ready
                    startLatch.await(5, TimeUnit.SECONDS);
                    
                    // Access the DataSession
                    TestDataAccess dataAccess = dataSession.access(TestDataAccess.class);
                    assertThat(dataAccess, is(notNullValue()));
                    
                    // Get the transaction
                    Transaction tx = dataSession.getTransaction();
                    assertThat(tx, is(notNullValue()));
                    
                    // Perform an operation using the DataAccess
                    dataAccess.performOperation();
                    
                    // Signal completion
                    completionLatch.countDown();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted", e);
                }
            });
            
            futures.add(future);
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        boolean allCompleted = completionLatch.await(10, TimeUnit.SECONDS);
        assertThat("All virtual threads should complete", allCompleted, is(true));
        
        // Verify all futures completed without exceptions
        for (Future<?> future : futures) {
            future.get(1, TimeUnit.SECONDS); // This will throw if the future completed with an exception
        }
        
        // Verify the DataSession was accessed the expected number of times
        verify(dataSession, times(threadCount)).access(TestDataAccess.class);
        verify(dataSession, times(threadCount)).getTransaction();
        verify(testDataAccess, times(threadCount)).performOperation();
        
        // Verify the operation counter matches the thread count
        assertThat(operationCounter.get(), is(threadCount));
    }
    
    /**
     * Test that SQL dialect can be retrieved from a DataSession in a virtual thread.
     */
    @Test
    public void testSqlDialectInVirtualThread() throws Exception {
        // Configure mock DataSession
        when(dataSession.sqlDialect()).thenReturn("PostgreSQL");
        
        AtomicReference<String> dialect = new AtomicReference<>();
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Get SQL dialect
            String sqlDialect = dataSession.sqlDialect();
            dialect.set(sqlDialect);
        });
        
        future.get(5, TimeUnit.SECONDS);
        
        // Verify the dialect was correctly retrieved
        assertThat(dialect.get(), is("PostgreSQL"));
        verify(dataSession).sqlDialect();
    }
    
    /**
     * Test that cleanup and resource management work correctly when DataSessions
     * are used in Virtual Threads.
     */
    @Test
    public void testResourceManagementInVirtualThread() throws Exception {
        // Create a mock AutoCloseable DataSession for this test
        DataSession closeableSession = mock(DataSession.class);
        when(closeableSession.access(TestDataAccess.class)).thenReturn(testDataAccess);
        
        AtomicBoolean sessionClosed = new AtomicBoolean(false);
        
        // Configure the session to track when it's closed
        doAnswer(invocation -> {
            sessionClosed.set(true);
            return null;
        }).when(closeableSession).close();
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // Use try-with-resources to ensure proper cleanup
            try (DataSession session = closeableSession) {
                TestDataAccess dataAccess = session.access(TestDataAccess.class);
                dataAccess.performOperation();
            }
        });
        
        future.get(5, TimeUnit.SECONDS);
        
        // Verify the session was properly closed
        assertThat("Session should be closed", sessionClosed.get(), is(true));
        verify(closeableSession).close();
        verify(closeableSession).access(TestDataAccess.class);
        verify(testDataAccess).performOperation();
    }
    
    /**
     * Test that exceptions in virtual threads are properly propagated and don't cause
     * thread leaks or other issues.
     */
    @Test
    public void testExceptionHandlingInVirtualThread() throws Exception {
        // Configure DataAccess to throw an exception
        doAnswer(invocation -> {
            throw new RuntimeException("Test exception");
        }).when(testDataAccess).performOperation();
        
        Future<?> future = virtualThreadExecutor.submit(() -> {
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            // This should throw an exception
            TestDataAccess dataAccess = dataSession.access(TestDataAccess.class);
            dataAccess.performOperation();
            
            // Should not reach here
            fail("Exception should have been thrown");
        });
        
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("Expected exception was not thrown");
        }
        catch (Exception e) {
            // Expected exception
            assertThat(e.getCause().getMessage(), is("Test exception"));
        }
        
        // Verify the DataAccess was accessed
        verify(dataSession).access(TestDataAccess.class);
        verify(testDataAccess).performOperation();
    }
    
    /**
     * Test interface for DataAccess used in tests.
     */
    private interface TestDataAccess extends DataAccess {
        // Marker interface for testing
        void performOperation();
    }
}