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
package org.apache.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.cache.configuration.Factory;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.nexus.ShiroJCacheAdapter;
import org.apache.shiro.nexus.ShiroJCacheManagerAdapter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cache.CacheHelper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ShiroJCacheManagerAdapter} and {@link ShiroJCacheAdapter} with Java 21 virtual threads.
 * Verifies that Shiro's caching components work correctly in a concurrent virtual thread environment.
 */
public class ShiroVirtualThreadCachingTest
    extends TestSupport
{
  private static final String TEST_CACHE_NAME = "testCache";
  private static final int THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 50;
  
  @Mock
  private CacheHelper cacheHelper;
  
  @Mock
  private javax.cache.Cache<Object, Object> jcache;
  
  private ShiroJCacheManagerAdapter underTest;
  
  private Cache<String, String> cache;
  
  @Before
  public void setUp() {
    // Configure the cache manager with a 2-minute expiry policy
    underTest = new ShiroJCacheManagerAdapter(() -> cacheHelper, () -> Time.minutes(2L));
    
    // Mock the cache helper to return our mock JCache
    when(cacheHelper.maybeCreateCache(eq(TEST_CACHE_NAME), any(Factory.class)))
        .thenReturn(jcache);
    
    // Get the cache from the manager
    cache = underTest.getCache(TEST_CACHE_NAME);
  }
  
  /**
   * Tests that cache operations (get/put/remove) work correctly when accessed
   * concurrently from multiple virtual threads.
   */
  @Test
  public void testConcurrentCacheOperationsWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("cache-test-", 0).factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a map to track expected values
      Map<String, String> expectedValues = new ConcurrentHashMap<>();
      
      // Create a latch to coordinate thread completion
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      
      // Create and submit tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String key = "key-" + threadId + "-" + j;
              String value = "value-" + threadId + "-" + j;
              
              // Test put operation
              when(jcache.getAndPut(key, value)).thenReturn(null);
              cache.put(key, value);
              expectedValues.put(key, value);
              
              // Test get operation
              when(jcache.get(key)).thenReturn(value);
              String retrievedValue = cache.get(key);
              assertThat(retrievedValue, equalTo(value));
              
              // Test updating an existing value
              String updatedValue = value + "-updated";
              when(jcache.getAndPut(key, updatedValue)).thenReturn(value);
              cache.put(key, updatedValue);
              expectedValues.put(key, updatedValue);
              
              // Test remove operation for some keys
              if (j % 5 == 0) {
                when(jcache.getAndRemove(key)).thenReturn(updatedValue);
                String removedValue = cache.remove(key);
                assertThat(removedValue, equalTo(updatedValue));
                expectedValues.remove(key);
                
                // Verify get returns null after remove
                when(jcache.get(key)).thenReturn(null);
                assertThat(cache.get(key), is(nullValue()));
              }
            }
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within the timeout", completed, is(true));
      
      // Verify the expected number of operations were performed
      int expectedPutOperations = THREAD_COUNT * OPERATIONS_PER_THREAD;
      int expectedRemoveOperations = THREAD_COUNT * (OPERATIONS_PER_THREAD / 5);
      int expectedGetOperations = THREAD_COUNT * OPERATIONS_PER_THREAD * 2; // get after put and get after remove
      
      verify(jcache, times(expectedPutOperations)).getAndPut(anyString(), anyString());
      verify(jcache, times(expectedRemoveOperations)).getAndRemove(anyString());
      verify(jcache, times(expectedGetOperations)).get(anyString());
    }
  }
  
  /**
   * Tests that cache expiry policies work correctly with virtual threads.
   */
  @Test
  public void testCacheExpiryPolicyWithVirtualThreads() throws Exception {
    // Create a cache with a short expiry time for testing
    String shortExpiryCache = "shortExpiryCache";
    Time shortExpiry = Time.seconds(1L);
    
    // Configure a new cache manager with a short expiry policy
    ShiroJCacheManagerAdapter shortExpiryManager = new ShiroJCacheManagerAdapter(
        () -> cacheHelper, 
        () -> shortExpiry
    );
    
    // Mock the cache helper to capture the expiry policy
    AtomicInteger expiryPolicyFactoryCalls = new AtomicInteger(0);
    Factory<ExpiryPolicy> capturedFactory = Mockito.mock(Factory.class);
    
    when(cacheHelper.maybeCreateCache(eq(shortExpiryCache), any(Factory.class)))
        .thenAnswer(invocation -> {
          Factory<ExpiryPolicy> factory = invocation.getArgument(1);
          expiryPolicyFactoryCalls.incrementAndGet();
          // Verify it's a CreatedExpiryPolicy with the correct duration
          assertThat(factory.toString(), equalTo(
              CreatedExpiryPolicy.factoryOf(new javax.cache.expiry.Duration(TimeUnit.SECONDS, 1L)).toString()));
          return jcache;
        });
    
    // Get the cache from the manager
    Cache<String, String> shortCache = shortExpiryManager.getCache(shortExpiryCache);
    
    // Verify the expiry policy factory was called
    assertThat(expiryPolicyFactoryCalls.get(), is(1));
    
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("expiry-test-", 0).factory();
    
    // Test cache operations with expiry using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
      
      // Create a latch to coordinate thread completion
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      
      // Create and submit tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            String key = "expiry-key-" + threadId;
            String value = "expiry-value-" + threadId;
            
            // Test put operation
            when(jcache.getAndPut(key, value)).thenReturn(null);
            shortCache.put(key, value);
            
            // Test get operation immediately should return the value
            when(jcache.get(key)).thenReturn(value);
            String retrievedValue = shortCache.get(key);
            assertThat(retrievedValue, equalTo(value));
            
            // Sleep longer than the expiry time
            Thread.sleep(1500);
            
            // After expiry, the cache should return null
            when(jcache.get(key)).thenReturn(null);
            String expiredValue = shortCache.get(key);
            assertThat(expiredValue, is(nullValue()));
          } finally {
            latch.countDown();
          }
          return null;
        }));
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within the timeout", completed, is(true));
    }
  }
  
  /**
   * Tests that cache eviction works correctly with virtual threads.
   */
  @Test
  public void testCacheEvictionWithVirtualThreads() throws Exception {
    // Create a thread factory that produces virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("eviction-test-", 0).factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a latch to coordinate thread completion
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      
      // Create and submit tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Add some entries to the cache
            for (int j = 0; j < 10; j++) {
              String key = "evict-key-" + threadId + "-" + j;
              String value = "evict-value-" + threadId + "-" + j;
              
              when(jcache.getAndPut(key, value)).thenReturn(null);
              cache.put(key, value);
            }
            
            // Test clear operation
            Mockito.doNothing().when(jcache).clear();
            cache.clear();
            
            // Verify the cache is empty after clear
            when(jcache.get(anyString())).thenReturn(null);
            for (int j = 0; j < 10; j++) {
              String key = "evict-key-" + threadId + "-" + j;
              assertThat(cache.get(key), is(nullValue()));
            }
          } finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All virtual threads should complete within the timeout", completed, is(true));
      
      // Verify clear was called the expected number of times
      verify(jcache, times(THREAD_COUNT)).clear();
    }
  }
}