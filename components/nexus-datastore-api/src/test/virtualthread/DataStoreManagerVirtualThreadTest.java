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

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataStoreManager} operations when executed within Virtual Threads.
 * 
 * @since 3.60
 */
public class DataStoreManagerVirtualThreadTest
    extends VirtualThreadTestSupport
{
  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private DataStore<?> dataStore;

  @BeforeEach
  public void setUp() {
    // Skip tests if Virtual Threads are not supported
    assumeVirtualThreadSupported();
    
    // Initialize mocks
    MockitoAnnotations.openMocks(this);
    
    // Setup default behavior
    when(dataStoreManager.get("testStore")).thenReturn(Optional.of(dataStore));
    when(dataStoreManager.exists("testStore")).thenReturn(true);
  }

  /**
   * Test that {@link DataStoreManager#browse()} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testBrowseInVirtualThread() throws Exception {
    // Setup mock behavior
    Iterable<DataStore<?>> expectedStores = mock(Iterable.class);
    when(dataStoreManager.browse()).thenReturn(expectedStores);
    
    // Execute browse() in a Virtual Thread
    AtomicReference<Iterable<DataStore<?>>> result = new AtomicReference<>();
    runVirtual(() -> result.set(dataStoreManager.browse()));
    
    // Verify the result
    assertThat(result.get(), is(expectedStores));
    verify(dataStoreManager).browse();
  }

  /**
   * Test that {@link DataStoreManager#create(DataStoreConfiguration)} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testCreateInVirtualThread() throws Exception {
    // Setup mock behavior
    DataStoreConfiguration config = mock(DataStoreConfiguration.class);
    when(config.getName()).thenReturn("newStore");
    when(dataStoreManager.create(config)).thenReturn(dataStore);
    
    // Execute create() in a Virtual Thread
    AtomicReference<DataStore<?>> result = new AtomicReference<>();
    runVirtual(() -> {
      try {
        result.set(dataStoreManager.create(config));
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify the result
    assertThat(result.get(), is(dataStore));
    verify(dataStoreManager).create(config);
  }

  /**
   * Test that {@link DataStoreManager#update(DataStoreConfiguration)} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testUpdateInVirtualThread() throws Exception {
    // Setup mock behavior
    DataStoreConfiguration config = mock(DataStoreConfiguration.class);
    when(config.getName()).thenReturn("testStore");
    when(dataStoreManager.update(config)).thenReturn(dataStore);
    
    // Execute update() in a Virtual Thread
    AtomicReference<DataStore<?>> result = new AtomicReference<>();
    runVirtual(() -> {
      try {
        result.set(dataStoreManager.update(config));
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify the result
    assertThat(result.get(), is(dataStore));
    verify(dataStoreManager).update(config);
  }

  /**
   * Test that {@link DataStoreManager#get(String)} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testGetInVirtualThread() throws Exception {
    // Execute get() in a Virtual Thread
    AtomicReference<Optional<DataStore<?>>> result = new AtomicReference<>();
    runVirtual(() -> result.set(dataStoreManager.get("testStore")));
    
    // Verify the result
    assertThat(result.get().isPresent(), is(true));
    assertThat(result.get().get(), is(dataStore));
    verify(dataStoreManager).get("testStore");
  }

  /**
   * Test that {@link DataStoreManager#delete(String)} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testDeleteInVirtualThread() throws Exception {
    // Setup mock behavior
    when(dataStoreManager.delete("testStore")).thenReturn(true);
    
    // Execute delete() in a Virtual Thread
    AtomicBoolean result = new AtomicBoolean(false);
    runVirtual(() -> {
      try {
        result.set(dataStoreManager.delete("testStore"));
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify the result
    assertThat(result.get(), is(true));
    verify(dataStoreManager).delete("testStore");
  }

  /**
   * Test that {@link DataStoreManager#exists(String)} works correctly when executed in a Virtual Thread.
   */
  @Test
  public void testExistsInVirtualThread() throws Exception {
    // Execute exists() in a Virtual Thread
    AtomicBoolean result = new AtomicBoolean(false);
    runVirtual(() -> result.set(dataStoreManager.exists("testStore")));
    
    // Verify the result
    assertThat(result.get(), is(true));
    verify(dataStoreManager).exists("testStore");
  }

  /**
   * Test that exceptions are properly propagated when executing DataStoreManager operations in Virtual Threads.
   */
  @Test
  public void testExceptionPropagationInVirtualThread() throws Exception {
    // Setup mock behavior to throw an exception
    DataStoreConfiguration config = mock(DataStoreConfiguration.class);
    Exception expectedException = new IllegalStateException("Test exception");
    doThrow(expectedException).when(dataStoreManager).create(config);
    
    // Execute create() in a Virtual Thread and expect an exception
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    runVirtual(() -> {
      try {
        dataStoreManager.create(config);
      }
      catch (Throwable e) {
        caughtException.set(e);
      }
    });
    
    // Verify the exception was propagated correctly
    assertThat(caughtException.get(), notNullValue());
    assertThat(caughtException.get(), is(expectedException));
  }

  /**
   * Test concurrent operations from multiple Virtual Threads.
   */
  @Test
  public void testConcurrentOperationsInVirtualThreads() throws Exception {
    // Setup mock behavior
    DataStoreConfiguration config1 = mock(DataStoreConfiguration.class);
    DataStoreConfiguration config2 = mock(DataStoreConfiguration.class);
    when(config1.getName()).thenReturn("store1");
    when(config2.getName()).thenReturn("store2");
    
    DataStore<?> dataStore1 = mock(DataStore.class);
    DataStore<?> dataStore2 = mock(DataStore.class);
    
    when(dataStoreManager.create(config1)).thenReturn(dataStore1);
    when(dataStoreManager.create(config2)).thenReturn(dataStore2);
    
    // Execute concurrent operations in Virtual Threads
    AtomicReference<DataStore<?>> result1 = new AtomicReference<>();
    AtomicReference<DataStore<?>> result2 = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);
    
    Thread thread1 = Thread.ofVirtual().start(() -> {
      try {
        result1.set(dataStoreManager.create(config1));
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      finally {
        latch.countDown();
      }
    });
    
    Thread thread2 = Thread.ofVirtual().start(() -> {
      try {
        result2.set(dataStoreManager.create(config2));
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      finally {
        latch.countDown();
      }
    });
    
    // Wait for both threads to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the results
    assertThat(result1.get(), is(dataStore1));
    assertThat(result2.get(), is(dataStore2));
    verify(dataStoreManager).create(config1);
    verify(dataStoreManager).create(config2);
  }

  /**
   * Test that state is maintained correctly when operations span multiple Virtual Threads.
   */
  @Test
  public void testStateMaintenanceAcrossVirtualThreads() throws Exception {
    // Setup mock behavior
    DataStoreConfiguration config = mock(DataStoreConfiguration.class);
    when(config.getName()).thenReturn("stateStore");
    when(dataStoreManager.create(config)).thenReturn(dataStore);
    when(dataStoreManager.get("stateStore")).thenReturn(Optional.of(dataStore));
    
    // First Virtual Thread creates the store
    runVirtual(() -> {
      try {
        dataStoreManager.create(config);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Second Virtual Thread retrieves the store
    AtomicReference<Optional<DataStore<?>>> result = new AtomicReference<>();
    runVirtual(() -> result.set(dataStoreManager.get("stateStore")));
    
    // Verify the result
    assertThat(result.get().isPresent(), is(true));
    assertThat(result.get().get(), is(dataStore));
    verify(dataStoreManager).create(config);
    verify(dataStoreManager).get("stateStore");
  }

  /**
   * Test that operations with timeouts work correctly in Virtual Threads.
   */
  @Test
  public void testOperationsWithTimeoutsInVirtualThread() throws Exception {
    // Setup a task that will take longer than the timeout
    DataStoreConfiguration config = mock(DataStoreConfiguration.class);
    when(dataStoreManager.create(config)).thenAnswer(invocation -> {
      Thread.sleep(2000); // Simulate a long-running operation
      return dataStore;
    });
    
    // Execute the operation with a timeout in a Virtual Thread
    Future<DataStore<?>>[] futures = callConcurrently(1, () -> {
      try {
        return dataStoreManager.create(config);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Verify that the operation times out
    assertThrows(TimeoutException.class, () -> futures[0].get(500, TimeUnit.MILLISECONDS));
    
    // But eventually completes successfully
    DataStore<?> result = futures[0].get(3, TimeUnit.SECONDS);
    assertThat(result, equalTo(dataStore));
  }
}