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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.cache.Cache;
import javax.cache.configuration.Factory;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.cache.CacheHelper;
import org.sonatype.nexus.testsupport.test.VirtualThreadTestGroup;

import org.apache.shiro.nexus.ShiroJCacheManagerAdapter;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ShiroJCacheManagerAdapter} compatibility with Java 21 virtual threads.
 * Verifies that cache creation, retrieval, and expiration function correctly in the
 * virtual thread execution environment.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class ShiroJCacheManagerAdapterVirtualThreadTest
    extends TestSupport
{
  @Mock
  private CacheHelper cacheHelper;

  @Captor
  private ArgumentCaptor<Factory<ExpiryPolicy>> expiryPolicyCaptor;

  private ShiroJCacheManagerAdapter underTest;

  @BeforeEach
  public void setUp() {
    underTest = new ShiroJCacheManagerAdapter(() -> cacheHelper, () -> Time.minutes(2L));
  }

  /**
   * Verifies that cache creation works correctly in a virtual thread.
   */
  @Test
  public void testCacheCreationInVirtualThread() throws Exception {
    // Mock cache creation
    Cache<Object, Object> mockCache = mock(Cache.class);
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Cache<Object, Object>> resultCache = new AtomicReference<>();
    AtomicBoolean threadCompleted = new AtomicBoolean(false);

    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual()
        .name("cache-creation-thread")
        .start(() -> {
          try {
            resultCache.set(underTest.maybeCreateCache("testCache"));
          } finally {
            threadCompleted.set(true);
            latch.countDown();
          }
        });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");
    assertTrue(threadCompleted.get(), "Virtual thread did not complete successfully");
    assertEquals(mockCache, resultCache.get(), "Cache was not created correctly in virtual thread");

    // Verify the cache helper was called with the correct parameters
    verify(cacheHelper).maybeCreateCache(eq("testCache"), any());
  }

  /**
   * Verifies that the default cache expiry policy (2 minutes) is correctly applied in a virtual thread.
   */
  @Test
  public void testDefaultExpiryPolicyInVirtualThread() throws Exception {
    // Mock cache creation
    when(cacheHelper.maybeCreateCache(anyString(), expiryPolicyCaptor.capture())).thenReturn(mock(Cache.class));

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);

    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual()
        .name("default-expiry-thread")
        .start(() -> {
          try {
            underTest.maybeCreateCache("testCache");
          } finally {
            latch.countDown();
          }
        });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");

    // Verify the correct expiry policy was used
    Factory<ExpiryPolicy> capturedFactory = expiryPolicyCaptor.getValue();
    assertNotNull(capturedFactory, "Expiry policy factory was not captured");
    ExpiryPolicy policy = capturedFactory.create();
    assertTrue(policy instanceof CreatedExpiryPolicy, "Expected CreatedExpiryPolicy but got: " + policy.getClass().getName());
    assertEquals(new Duration(TimeUnit.MINUTES, 2L), ((CreatedExpiryPolicy) policy).getExpiryForCreation());
  }

  /**
   * Verifies that the Shiro session cache uses eternal expiry policy in a virtual thread.
   */
  @Test
  public void testShiroSessionCacheExpiryPolicyInVirtualThread() throws Exception {
    // Mock cache creation
    when(cacheHelper.maybeCreateCache(anyString(), expiryPolicyCaptor.capture())).thenReturn(mock(Cache.class));

    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);

    // Create and start a virtual thread
    Thread virtualThread = Thread.ofVirtual()
        .name("session-cache-thread")
        .start(() -> {
          try {
            underTest.maybeCreateCache(CachingSessionDAO.ACTIVE_SESSION_CACHE_NAME);
          } finally {
            latch.countDown();
          }
        });

    // Wait for the virtual thread to complete
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread did not complete in time");

    // Verify the correct expiry policy was used
    Factory<ExpiryPolicy> capturedFactory = expiryPolicyCaptor.getValue();
    assertNotNull(capturedFactory, "Expiry policy factory was not captured");
    ExpiryPolicy policy = capturedFactory.create();
    assertTrue(policy instanceof EternalExpiryPolicy, "Expected EternalExpiryPolicy but got: " + policy.getClass().getName());
  }

  /**
   * Verifies that concurrent cache operations in multiple virtual threads work correctly without pinning.
   * Uses CountDownLatch to coordinate between threads and verify no thread pinning occurs.
   */
  @Test
  public void testConcurrentCacheOperationsInVirtualThreads() throws Exception {
    // Number of virtual threads to create
    final int threadCount = 10;
    
    // Mock cache creation
    Cache<Object, Object> mockCache = mock(Cache.class);
    when(cacheHelper.maybeCreateCache(anyString(), any())).thenReturn(mockCache);

    // Create latches to coordinate thread execution
    CountDownLatch startLatch = new CountDownLatch(1); // Used to start all threads simultaneously
    CountDownLatch completionLatch = new CountDownLatch(threadCount); // Used to wait for all threads to complete

    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final String cacheName = "testCache" + i;
      Thread.ofVirtual()
          .name("concurrent-cache-thread-" + i)
          .start(() -> {
            try {
              // Wait for the signal to start
              startLatch.await();
              
              // Create cache
              Cache<Object, Object> cache = underTest.maybeCreateCache(cacheName);
              assertNotNull(cache, "Cache should not be null");
              
              // Simulate some work with the cache
              cache.put("key", "value");
              Object value = cache.get("key");
              assertEquals("value", value);
            } catch (Exception e) {
              log.error("Error in virtual thread", e);
            } finally {
              completionLatch.countDown();
            }
          });
    }

    // Start all threads simultaneously
    startLatch.countDown();

    // Wait for all threads to complete
    assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Not all virtual threads completed in time");

    // Verify the cache helper was called the expected number of times
    verify(cacheHelper, times(threadCount)).maybeCreateCache(anyString(), any());
  }
}