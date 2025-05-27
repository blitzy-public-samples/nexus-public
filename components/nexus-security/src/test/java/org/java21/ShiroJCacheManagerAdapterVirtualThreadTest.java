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
package org.java21;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.cache.Cache;
import javax.cache.configuration.Factory;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cache.CacheHelper;
import org.sonatype.nexus.security.VirtualThreadTestGroup;

import org.apache.shiro.nexus.ShiroJCacheManagerAdapter;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ShiroJCacheManagerAdapter} compatibility with Java 21 virtual threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class ShiroJCacheManagerAdapterVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_CACHE_NAME = "testCache";
  private static final String SESSION_CACHE_NAME = CachingSessionDAO.ACTIVE_SESSION_CACHE_NAME;
  private static final long CACHE_EXPIRY_MINUTES = 2L;

  @Mock
  private CacheHelper cacheHelper;

  @Mock
  private Cache<Object, Object> mockCache;

  @Captor
  private ArgumentCaptor<Factory<ExpiryPolicy>> confCaptor;

  private ShiroJCacheManagerAdapter underTest;

  @BeforeEach
  public void setUp() {
    underTest = new ShiroJCacheManagerAdapter(
        () -> cacheHelper, 
        () -> org.sonatype.goodies.common.Time.minutes(CACHE_EXPIRY_MINUTES));
  }

  /**
   * Verifies that the default cache configuration works correctly with virtual threads.
   */
  @Test
  public void defaultCacheConfigurationWithVirtualThreads() throws Exception {
    // Setup mock behavior
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread and execute the cache creation
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      Cache<Object, Object> cache = underTest.maybeCreateCache(TEST_CACHE_NAME);
      assertNotNull(cache, "Cache should be created successfully in virtual thread");
    });
    
    // Start the thread and wait for it to complete
    virtualThread.start();
    virtualThread.join();
    
    // Verify the cache was created with the correct configuration
    verify(cacheHelper).maybeCreateCache(eq(TEST_CACHE_NAME), confCaptor.capture());
    assertThat(confCaptor.getValue(), is(CreatedExpiryPolicy.factoryOf(new Duration(TimeUnit.MINUTES, CACHE_EXPIRY_MINUTES))));
  }

  /**
   * Verifies that the Shiro session cache configuration works correctly with virtual threads.
   */
  @Test
  public void sessionCacheConfigurationWithVirtualThreads() throws Exception {
    // Setup mock behavior
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread and execute the session cache creation
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      Cache<Object, Object> cache = underTest.maybeCreateCache(SESSION_CACHE_NAME);
      assertNotNull(cache, "Session cache should be created successfully in virtual thread");
    });
    
    // Start the thread and wait for it to complete
    virtualThread.start();
    virtualThread.join();
    
    // Verify the session cache was created with the correct configuration (eternal expiry)
    verify(cacheHelper).maybeCreateCache(eq(SESSION_CACHE_NAME), confCaptor.capture());
    assertThat(confCaptor.getValue(), is(EternalExpiryPolicy.factoryOf()));
  }

  /**
   * Tests concurrent cache operations using multiple virtual threads to ensure thread safety.
   */
  @Test
  public void concurrentCacheOperationsWithVirtualThreads() throws Exception {
    // Setup mock behavior
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    // Number of concurrent operations to perform
    int concurrentOperations = 100;
    
    // Use CountDownLatch to coordinate the threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentOperations);
    
    // Track any errors that occur during execution
    AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Submit tasks to create caches concurrently
    for (int i = 0; i < concurrentOperations; i++) {
      final String cacheName = i % 2 == 0 ? TEST_CACHE_NAME : SESSION_CACHE_NAME;
      
      executor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Create the cache
          Cache<Object, Object> cache = underTest.maybeCreateCache(cacheName);
          assertNotNull(cache, "Cache should be created successfully");
        } 
        catch (Throwable t) {
          error.compareAndSet(null, t);
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Signal all threads to start simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete (with timeout)
    boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Check for any errors
    if (error.get() != null) {
      throw new AssertionError("Error during concurrent cache operations", error.get());
    }
    
    // Verify all operations completed
    assertTrue(completed, "All cache operations should complete within the timeout");
    
    // Verify the cache helper was called the expected number of times
    verify(cacheHelper, times(concurrentOperations)).maybeCreateCache(anyString(), any());
  }

  /**
   * Tests that cache operations don't cause thread pinning when executed in virtual threads.
   */
  @Test
  public void noPinningDuringCacheOperations() throws Exception {
    // Setup mock behavior
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);
    
    // Create a virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    
    // Number of operations to perform
    int operationCount = 50;
    
    // Use CountDownLatch to track completion
    CountDownLatch completionLatch = new CountDownLatch(operationCount);
    
    // Track successful operations
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit tasks that alternate between regular and session caches
    for (int i = 0; i < operationCount; i++) {
      final String cacheName = i % 2 == 0 ? TEST_CACHE_NAME : SESSION_CACHE_NAME;
      
      executor.submit(() -> {
        try {
          // Create the cache
          Cache<Object, Object> cache = underTest.maybeCreateCache(cacheName);
          
          // Perform some operations on the cache
          cache.put("key", "value");
          Object value = cache.get("key");
          
          // Verify the operation was successful
          if (value != null) {
            successCount.incrementAndGet();
          }
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete (with timeout)
    boolean completed = completionLatch.await(5, TimeUnit.SECONDS);
    
    // Shutdown the executor
    executor.shutdown();
    
    // Verify all operations completed (indicating no thread pinning occurred)
    assertTrue(completed, "All cache operations should complete without thread pinning");
    
    // Verify the expected number of successful operations
    assertEquals(operationCount, successCount.get(), "All cache operations should succeed");
  }
}