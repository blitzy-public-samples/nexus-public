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
package org.apache.shiro.java21;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.cache.Cache;
import javax.cache.configuration.Factory;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.cache.CacheHelper;

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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ShiroJCacheManagerAdapter} with Java 21 features including Virtual Threads
 * and pattern matching for instanceof.
 */
@ExtendWith(MockitoExtension.class)
public class ShiroJCacheManagerAdapterJava21Test
{
  @Mock
  private CacheHelper cacheHelper;

  @Captor
  private ArgumentCaptor<Factory<ExpiryPolicy>> confCaptor;

  private ShiroJCacheManagerAdapter underTest;

  @BeforeEach
  public void setUp() {
    underTest = new ShiroJCacheManagerAdapter(() -> cacheHelper, () -> Time.minutes(2L));
  }

  @Test
  public void defaultCacheConfigurationTest() {
    when(cacheHelper.maybeCreateCache(anyString(), confCaptor.capture())).thenReturn(null);
    underTest.maybeCreateCache("foo");
    
    // Using Java 21 pattern matching for instanceof to verify the expiry policy
    Factory<ExpiryPolicy> factory = confCaptor.getValue();
    ExpiryPolicy policy = factory.create();
    if (policy instanceof CreatedExpiryPolicy createdPolicy) {
      Duration duration = createdPolicy.getExpiryForCreation();
      assertThat(duration.getTimeUnit(), is(TimeUnit.MINUTES));
      assertThat(duration.getDurationAmount(), is(2L));
    } else {
      throw new AssertionError("Expected CreatedExpiryPolicy but got " + policy.getClass().getName());
    }
  }

  @Test
  public void defaultShiroActiveSessionCacheConfigurationTest() {
    when(cacheHelper.maybeCreateCache(anyString(), confCaptor.capture())).thenReturn(null);
    underTest.maybeCreateCache(CachingSessionDAO.ACTIVE_SESSION_CACHE_NAME);
    
    // Using Java 21 pattern matching for instanceof to verify the expiry policy
    Factory<ExpiryPolicy> factory = confCaptor.getValue();
    ExpiryPolicy policy = factory.create();
    if (policy instanceof EternalExpiryPolicy) {
      // Test passes if we get here
      assertTrue(true, "Expected EternalExpiryPolicy and got it");
    } else {
      throw new AssertionError("Expected EternalExpiryPolicy but got " + policy.getClass().getName());
    }
  }

  @Test
  public void concurrentCacheAccessWithVirtualThreadsTest() throws Exception {
    // Setup a mock cache for testing
    Cache<Object, Object> mockCache = mock(Cache.class);
    when(cacheHelper.maybeCreateCache(eq("virtualThreadTest"), confCaptor.capture())).thenReturn(mockCache);
    
    // Get the cache through the adapter
    Cache<Object, Object> cache = underTest.getCache("virtualThreadTest");
    
    // Number of virtual threads to use for testing
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            // Perform cache operations
            String key = "key" + index;
            String value = "value" + index;
            cache.put(key, value);
            cache.get(key);
          } catch (Exception e) {
            failed.set(true);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      assertThat("No exceptions should have been thrown during concurrent cache access", failed.get(), is(false));
      
      // Verify cache operations were performed
      verify(mockCache).put(eq("key0"), eq("value0"));
    }
  }

  @Test
  public void cacheExpiryPolicyWithVirtualThreadsTest() throws Exception {
    // Setup a mock cache for testing
    Cache<Object, Object> mockCache = mock(Cache.class);
    when(cacheHelper.maybeCreateCache(eq("expiryTest"), confCaptor.capture())).thenReturn(mockCache);
    
    // Get the cache through the adapter
    underTest.getCache("expiryTest");
    
    // Verify the expiry policy was created with the correct duration
    Factory<ExpiryPolicy> factory = confCaptor.getValue();
    ExpiryPolicy policy = factory.create();
    
    // Using Java 21 pattern matching for instanceof with a guard clause
    if (policy instanceof CreatedExpiryPolicy createdPolicy && 
        createdPolicy.getExpiryForCreation().getTimeUnit() == TimeUnit.MINUTES && 
        createdPolicy.getExpiryForCreation().getDurationAmount() == 2L) {
      // Test passes if we get here with the correct expiry policy
      assertTrue(true, "Correct expiry policy created");
    } else {
      throw new AssertionError("Expected CreatedExpiryPolicy with 2 minutes duration");
    }
    
    // Test that creating the policy is thread-safe with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        executor.submit(() -> {
          assertDoesNotThrow(() -> factory.create(), "Creating expiry policy should be thread-safe");
        });
      }
    }
  }
}