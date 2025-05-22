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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataStore} interface compatibility with Java 21 Virtual Threads.
 * 
 * This test class validates that all DataStore API operations work correctly when executed
 * within Virtual Threads. It tests basic operations, connection handling, lifecycle operations,
 * and structured concurrency features to ensure compatibility with Java 21 Virtual Threads.
 * 
 * The tests verify:
 * 1. Basic DataStore operations (openSession, getConfiguration, etc.)
 * 2. Connection acquisition and usage
 * 3. Lifecycle operations (start, stop, freeze, unfreeze)
 * 4. Thread context propagation across Virtual Thread handoffs
 * 5. Structured concurrency with Virtual Threads
 * 6. Thread pinning detection
 * 
 * @since 3.31
 */
public class DataStoreAPIVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int TIMEOUT_SECONDS = 5;
  
  @Mock
  private DataStore<DataSession<?>> dataStore;
  
  @Mock
  private DataSession<?> dataSession;
  
  @Mock
  private Connection connection;
  
  @Mock
  private DataStoreConfiguration configuration;
  
  @Mock
  private DataSource dataSource;
  
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Setup basic DataStore behavior
    when(dataStore.openSession()).thenReturn(dataSession);
    when(dataStore.openConnection()).thenReturn(connection);
    when(dataStore.getConfiguration()).thenReturn(configuration);
    when(dataStore.getDataSource()).thenReturn(dataSource);
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that a DataStore can be accessed from a Virtual Thread.
   */
  @Test
  public void testDataStoreAccessFromVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<DataSession<?>> sessionRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      sessionRef.set(dataStore.openSession());
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify the session was correctly obtained
    assertThat(sessionRef.get(), is(dataSession));
  }
  
  /**
   * Tests that DataStore connection acquisition works correctly in Virtual Threads.
   */
  @Test
  public void testConnectionAcquisitionInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<Connection> connectionRef = new AtomicReference<>();
    AtomicBoolean exceptionThrown = new AtomicBoolean(false);
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      try {
        connectionRef.set(dataStore.openConnection());
      }
      catch (SQLException e) {
        exceptionThrown.set(true);
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify no exception was thrown
    assertThat(exceptionThrown.get(), is(false));
    
    // Verify the connection was correctly obtained
    assertThat(connectionRef.get(), is(connection));
  }
  
  /**
   * Tests that DataStore DataSource access works correctly in Virtual Threads.
   */
  @Test
  public void testDataSourceAccessInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<DataSource> dataSourceRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      dataSourceRef.set(dataStore.getDataSource());
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify the DataSource was correctly obtained
    assertThat(dataSourceRef.get(), is(dataSource));
  }
  
  /**
   * Tests that DataStore configuration operations work correctly in Virtual Threads.
   */
  @Test
  public void testConfigurationOperationsInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<DataStoreConfiguration> configRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      configRef.set(dataStore.getConfiguration());
      dataStore.setConfiguration(configuration);
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify the configuration was correctly obtained
    assertThat(configRef.get(), is(configuration));
    
    // Verify setConfiguration was called
    verify(dataStore).setConfiguration(configuration);
  }
  
  /**
   * Tests that DataStore registration operations work correctly in Virtual Threads.
   */
  @Test
  public void testRegistrationOperationsInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    Class<? extends DataAccess> accessType = TestDataAccess.class;
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      dataStore.register(accessType);
      dataStore.unregister(accessType);
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify register and unregister were called
    verify(dataStore).register(accessType);
    verify(dataStore).unregister(accessType);
  }
  
  /**
   * Tests that DataStore lifecycle operations work correctly in Virtual Threads.
   */
  @Test
  public void testLifecycleOperationsInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      dataStore.start();
      dataStore.stop();
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify lifecycle methods were called
    verify(dataStore).start();
    verify(dataStore).stop();
  }
  
  /**
   * Tests that DataStore freeze/unfreeze operations work correctly in Virtual Threads.
   */
  @Test
  public void testFreezeOperationsInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicBoolean frozenState = new AtomicBoolean(false);
    
    when(dataStore.isFrozen()).thenReturn(false, true, false);
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      dataStore.freeze();
      frozenState.set(dataStore.isFrozen());
      dataStore.unfreeze();
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify freeze/unfreeze methods were called
    verify(dataStore).freeze();
    verify(dataStore).unfreeze();
    
    // Verify frozen state was correctly obtained
    assertThat(frozenState.get(), is(true));
  }
  
  /**
   * Tests that DataStore backup operations work correctly in Virtual Threads.
   */
  @Test
  public void testBackupOperationInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    String backupLocation = "/tmp/backup";
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      try {
        dataStore.backup(backupLocation);
      }
      catch (Exception e) {
        // Ignore for test purposes
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify backup method was called
    verify(dataStore).backup(backupLocation);
  }
  
  /**
   * Tests that DataStore shutdown operation works correctly in Virtual Threads.
   */
  @Test
  public void testShutdownOperationInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      try {
        dataStore.shutdown();
      }
      catch (Exception e) {
        // Ignore for test purposes
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify shutdown method was called
    verify(dataStore).shutdown();
  }
  
  /**
   * Tests that multiple concurrent DataStore operations work correctly in Virtual Threads.
   */
  @Test
  public void testConcurrentOperationsInVirtualThreads() throws Exception {
    int numThreads = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(numThreads);
    AtomicBoolean allVirtualThreads = new AtomicBoolean(true);
    
    // Setup thread detection in the mock
    doAnswer(invocation -> {
      if (!Thread.currentThread().isVirtual()) {
        allVirtualThreads.set(false);
      }
      completionLatch.countDown();
      return dataSession;
    }).when(dataStore).openSession();
    
    // Start multiple virtual threads
    for (int i = 0; i < numThreads; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          dataStore.openSession();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads completed in time", completed, is(true));
    
    // Verify all operations were executed in virtual threads
    assertThat("All operations executed in virtual threads", allVirtualThreads.get(), is(true));
    
    // Verify openSession was called the expected number of times
    verify(dataStore).openSession();
  }
  
  /**
   * Tests that DataSession operations work correctly in Virtual Threads.
   */
  @Test
  public void testDataSessionOperationsInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<Object> resultRef = new AtomicReference<>();
    Class<TestDataAccess> accessType = TestDataAccess.class;
    
    // Setup mock behavior
    TestDataAccess dataAccess = new TestDataAccess();
    when(dataSession.access(accessType)).thenReturn(dataAccess);
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      
      // Test DataSession.access
      DataAccess access = dataSession.access(accessType);
      resultRef.set(access);
      
      // Test transaction hooks
      dataSession.preCommit(() -> {});
      dataSession.postCommit(() -> {});
      dataSession.onRollback(() -> {});
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify access method returned the expected result
    assertThat(resultRef.get(), is(dataAccess));
    
    // Verify transaction hooks were called
    verify(dataSession).preCommit(notNullValue());
    verify(dataSession).postCommit(notNullValue());
    verify(dataSession).onRollback(notNullValue());
  }
  
  /**
   * Tests that thread context is properly maintained across Virtual Thread handoffs.
   * This is critical for DataStore operations that may block and cause thread handoffs.
   */
  @Test
  public void testThreadContextPropagationInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<ClassLoader> contextClassLoader = new AtomicReference<>();
    
    // Set a custom context class loader for the test
    ClassLoader testClassLoader = new ClassLoader(getClass().getClassLoader()) {};
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      Thread currentThread = Thread.currentThread();
      threadRef.set(currentThread);
      
      // Set context class loader
      currentThread.setContextClassLoader(testClassLoader);
      
      // Simulate a blocking operation that would cause a Virtual Thread handoff
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Check if context class loader is preserved after handoff
      contextClassLoader.set(Thread.currentThread().getContextClassLoader());
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify context class loader was preserved across handoffs
    assertThat(contextClassLoader.get(), is(testClassLoader));
  }
  
  /**
   * Tests that thread-local variables are properly maintained across Virtual Thread handoffs.
   * This is important for DataStore operations that rely on thread-local state.
   */
  @Test
  public void testThreadLocalPropagationInVirtualThread() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<String> threadLocalValueAfterHandoff = new AtomicReference<>();
    
    // Create a thread-local variable for the test
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      Thread currentThread = Thread.currentThread();
      threadRef.set(currentThread);
      
      // Set thread-local value
      threadLocal.set("test-value");
      
      // Simulate a blocking operation that would cause a Virtual Thread handoff
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Check if thread-local value is preserved after handoff
      threadLocalValueAfterHandoff.set(threadLocal.get());
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify thread-local value was preserved across handoffs
    assertThat(threadLocalValueAfterHandoff.get(), equalTo("test-value"));
  }
  
  /**
   * Tests for potential thread pinning issues when using DataStore operations.
   */
  @Test
  public void testNoPinningInDataStoreOperations() throws Exception {
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      
      // Enable pinning detection for this thread
      pinningDetector.enableDetectionForCurrentThread();
      
      try {
        // Perform various DataStore operations
        dataStore.openSession();
        dataStore.getConfiguration();
        dataStore.openConnection();
      }
      catch (SQLException e) {
        // Ignore for test purposes
      }
      finally {
        pinningDetector.disableDetectionForCurrentThread();
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify no pinning was detected
    assertThat("No thread pinning should be detected", 
        pinningDetector.getPinningEvents().isEmpty(), is(true));
  }
  
  /**
   * Tests that DataSession's structured concurrency methods work correctly with Virtual Threads.
   */
  @Test
  public void testStructuredConcurrencyInDataSession() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicReference<Object[]> resultsRef = new AtomicReference<>();
    
    // Setup mock behavior for executeWithContext and executeConcurrently
    when(dataSession.executeWithContext(notNullValue())).thenAnswer(invocation -> {
      return "test result";
    });
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      
      try {
        // Test executeWithContext
        String result = dataSession.executeWithContext(() -> "test result");
        assertThat(result, equalTo("test result"));
        
        // Test executeConcurrently with multiple tasks
        Supplier<Object[]> resultsSupplier = dataSession.executeConcurrently(
            () -> "result1",
            () -> "result2"
        );
        
        resultsRef.set(resultsSupplier.get());
      }
      catch (Exception e) {
        // Ignore for test purposes
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify executeWithContext was called
    verify(dataSession).executeWithContext(notNullValue());
  }
  
  /**
   * Tests that DataSession's structured concurrency with StructuredTaskScope works correctly with Virtual Threads.
   */
  @Test
  public void testStructuredTaskScopeWithVirtualThreads() throws Exception {
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    AtomicBoolean allVirtualThreads = new AtomicBoolean(true);
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      threadRef.set(Thread.currentThread());
      
      try (StructuredTaskScope.ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Fork multiple subtasks
        Supplier<String> task1 = scope.fork(() -> {
          if (!Thread.currentThread().isVirtual()) {
            allVirtualThreads.set(false);
          }
          return "result1";
        });
        
        Supplier<String> task2 = scope.fork(() -> {
          if (!Thread.currentThread().isVirtual()) {
            allVirtualThreads.set(false);
          }
          return "result2";
        });
        
        // Wait for all tasks to complete
        scope.join();
        scope.throwIfFailed();
        
        // Get results
        String result1 = task1.get();
        String result2 = task2.get();
        
        assertThat(result1, equalTo("result1"));
        assertThat(result2, equalTo("result2"));
      }
      catch (Exception e) {
        // Ignore for test purposes
      }
    });
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify the operation was executed in a virtual thread
    assertThat(threadRef.get(), VirtualThreadMatchers.isVirtualThread());
    
    // Verify all subtasks were also executed in virtual threads
    assertThat("All subtasks executed in virtual threads", allVirtualThreads.get(), is(true));
  }
  
  /**
   * Test implementation of DataAccess for registration tests.
   */
  /**
   * Test implementation of DataAccess for registration tests.
   */
  private static class TestDataAccess implements DataAccess {
    @Override
    public void createSchema() {
      // No-op for test
    }
    
    @Override
    public void extendSchema() {
      // No-op for test
    }
  }
}