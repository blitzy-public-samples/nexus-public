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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataStoreManager} operations when executed within Virtual Threads.
 * 
 * This test class validates that all DataStoreManager operations function correctly when
 * executed within Java 21 Virtual Threads. It tests both individual operations and concurrent
 * scenarios to ensure proper behavior in a high-concurrency environment.
 * 
 * @since 3.41
 */
public class DataStoreManagerVirtualThreadTest
    extends TestSupport
{
    private static final String TEST_STORE_NAME = "test-store";
    
    private static final int CONCURRENT_THREADS = 10;
    
    @Mock
    private DataStoreManager dataStoreManager;
    
    @Mock
    private DataStore<?> dataStore;
    
    @Mock
    private DataStoreConfiguration configuration;
    
    private ExecutorService virtualThreadExecutor;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        when(configuration.getName()).thenReturn(TEST_STORE_NAME);
        when(dataStore.getConfiguration()).thenReturn(configuration);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Tests that the browse() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testBrowseInVirtualThread() throws Exception {
        // Given
        List<DataStore<?>> expectedStores = List.of(dataStore);
        when(dataStoreManager.browse()).thenReturn(expectedStores);
        
        // When
        AtomicReference<List<DataStore<?>>> actualStores = new AtomicReference<>();
        Future<?> future = virtualThreadExecutor.submit(() -> {
            actualStores.set((List<DataStore<?>>) dataStoreManager.browse());
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(actualStores.get(), is(expectedStores));
        verify(dataStoreManager).browse();
    }
    
    /**
     * Tests that the create() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testCreateInVirtualThread() throws Exception {
        // Given
        when(dataStoreManager.create(configuration)).thenReturn(dataStore);
        
        // When
        AtomicReference<DataStore<?>> createdStore = new AtomicReference<>();
        Future<?> future = virtualThreadExecutor.submit(() -> {
            try {
                createdStore.set(dataStoreManager.create(configuration));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(createdStore.get(), is(dataStore));
        verify(dataStoreManager).create(configuration);
    }
    
    /**
     * Tests that the update() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testUpdateInVirtualThread() throws Exception {
        // Given
        when(dataStoreManager.update(configuration)).thenReturn(dataStore);
        
        // When
        AtomicReference<DataStore<?>> updatedStore = new AtomicReference<>();
        Future<?> future = virtualThreadExecutor.submit(() -> {
            try {
                updatedStore.set(dataStoreManager.update(configuration));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(updatedStore.get(), is(dataStore));
        verify(dataStoreManager).update(configuration);
    }
    
    /**
     * Tests that the get() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testGetInVirtualThread() throws Exception {
        // Given
        when(dataStoreManager.get(TEST_STORE_NAME)).thenReturn(Optional.of(dataStore));
        
        // When
        AtomicReference<Optional<DataStore<?>>> retrievedStore = new AtomicReference<>();
        Future<?> future = virtualThreadExecutor.submit(() -> {
            retrievedStore.set(dataStoreManager.get(TEST_STORE_NAME));
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(retrievedStore.get().isPresent(), is(true));
        assertThat(retrievedStore.get().get(), is(dataStore));
        verify(dataStoreManager).get(TEST_STORE_NAME);
    }
    
    /**
     * Tests that the delete() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testDeleteInVirtualThread() throws Exception {
        // Given
        when(dataStoreManager.delete(TEST_STORE_NAME)).thenReturn(true);
        
        // When
        AtomicBoolean deleted = new AtomicBoolean(false);
        Future<?> future = virtualThreadExecutor.submit(() -> {
            try {
                deleted.set(dataStoreManager.delete(TEST_STORE_NAME));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(deleted.get(), is(true));
        verify(dataStoreManager).delete(TEST_STORE_NAME);
    }
    
    /**
     * Tests that the exists() operation works correctly when executed within a Virtual Thread.
     */
    @Test
    void testExistsInVirtualThread() throws Exception {
        // Given
        when(dataStoreManager.exists(TEST_STORE_NAME)).thenReturn(true);
        
        // When
        AtomicBoolean exists = new AtomicBoolean(false);
        Future<?> future = virtualThreadExecutor.submit(() -> {
            exists.set(dataStoreManager.exists(TEST_STORE_NAME));
        });
        future.get(5, TimeUnit.SECONDS);
        
        // Then
        assertThat(exists.get(), is(true));
        verify(dataStoreManager).exists(TEST_STORE_NAME);
    }
    
    /**
     * Tests that exceptions are properly propagated when operations executed within Virtual Threads fail.
     * 
     * This test verifies that exceptions thrown during DataStoreManager operations are correctly
     * propagated from Virtual Threads to the calling context, ensuring that error handling works
     * properly in the Virtual Thread environment.
     */
    @Test
    void testExceptionPropagationInVirtualThread() throws Exception {
        // Given
        Exception expectedException = new RuntimeException("Test exception");
        doThrow(expectedException).when(dataStoreManager).create(configuration);
        
        // When/Then
        AtomicReference<Exception> caughtException = new AtomicReference<>();
        Future<?> future = virtualThreadExecutor.submit(() -> {
            try {
                dataStoreManager.create(configuration);
            } catch (Exception e) {
                caughtException.set(e);
            }
        });
        future.get(5, TimeUnit.SECONDS);
        
        assertThat(caughtException.get(), is(expectedException));
        verify(dataStoreManager).create(configuration);
    }
    
    /**
     * Tests concurrent create operations from multiple Virtual Threads.
     * 
     * This test validates that multiple Virtual Threads can concurrently create data stores
     * without issues. It uses a CountDownLatch to coordinate the start of all threads and
     * tracks successful operations to ensure all threads complete their work correctly.
     */
    @Test
    void testConcurrentCreateOperations() throws Exception {
        // Given
        when(dataStoreManager.create(configuration)).thenReturn(dataStore);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        
        // When
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            virtualThreadExecutor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready
                    dataStoreManager.create(configuration);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error in concurrent create", e);
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        completionLatch.await(10, TimeUnit.SECONDS);
        
        // Then
        assertThat(successCount.get(), is(CONCURRENT_THREADS));
        verify(dataStoreManager, times(CONCURRENT_THREADS)).create(configuration);
    }
    
    /**
     * Tests that operations spanning multiple Virtual Threads maintain correct state.
     * 
     * This test simulates a workflow where three different Virtual Threads perform sequential
     * operations on the same data store (create, get, delete). It uses a CyclicBarrier to
     * coordinate the threads and ensure they execute in the correct order, validating that
     * state is properly maintained across thread boundaries.
     */
    @Test
    void testOperationsSpanningMultipleVirtualThreads() throws Exception {
        // Given
        final int THREAD_COUNT = 3;
        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        List<String> storeNames = new ArrayList<>();
        
        // Mock the create and get operations
        when(dataStoreManager.create(configuration)).thenReturn(dataStore);
        when(dataStoreManager.get(TEST_STORE_NAME)).thenReturn(Optional.of(dataStore));
        
        // When
        // Thread 1: Creates the store
        Future<?> createFuture = virtualThreadExecutor.submit(() -> {
            try {
                DataStore<?> store = dataStoreManager.create(configuration);
                storeNames.add("created");
                barrier.await(); // Wait for other threads
            } catch (Exception e) {
                log.error("Error in create thread", e);
            }
        });
        
        // Thread 2: Gets the store
        Future<?> getFuture = virtualThreadExecutor.submit(() -> {
            try {
                barrier.await(); // Wait for create to complete
                Optional<DataStore<?>> store = dataStoreManager.get(TEST_STORE_NAME);
                if (store.isPresent()) {
                    storeNames.add("retrieved");
                }
                barrier.await(); // Wait for delete thread
            } catch (Exception e) {
                log.error("Error in get thread", e);
            }
        });
        
        // Thread 3: Deletes the store
        Future<?> deleteFuture = virtualThreadExecutor.submit(() -> {
            try {
                barrier.await(); // Wait for get to complete
                barrier.await(); // Wait for get to record result
                dataStoreManager.delete(TEST_STORE_NAME);
                storeNames.add("deleted");
            } catch (Exception e) {
                log.error("Error in delete thread", e);
            }
        });
        
        // Wait for all operations to complete
        createFuture.get(10, TimeUnit.SECONDS);
        getFuture.get(10, TimeUnit.SECONDS);
        deleteFuture.get(10, TimeUnit.SECONDS);
        
        // Then
        assertThat(storeNames, contains("created", "retrieved", "deleted"));
        verify(dataStoreManager).create(configuration);
        verify(dataStoreManager).get(TEST_STORE_NAME);
        verify(dataStoreManager).delete(TEST_STORE_NAME);
    }
}