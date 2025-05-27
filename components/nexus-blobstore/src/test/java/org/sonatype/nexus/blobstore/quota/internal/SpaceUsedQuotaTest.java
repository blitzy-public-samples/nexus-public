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
package org.sonatype.nexus.blobstore.quota.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.rest.ValidationErrorsException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.LIMIT_KEY;
import static org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport.ROOT_KEY;

@ExtendWith(MockitoExtension.class)
public class SpaceUsedQuotaTest
    extends TestSupport
{
  SpaceUsedQuota quota;

  @Mock
  BlobStore blobStore;

  @Mock
  BlobStoreMetrics metrics;

  @Mock
  BlobStoreConfiguration config;

  @Mock
  NestedAttributesMap attributesMap;

  @BeforeEach
  void setup() {
    when(blobStore.getMetrics()).thenReturn(metrics);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(config);
    when(config.getName()).thenReturn("test");
    when(config.attributes(ROOT_KEY)).thenReturn(attributesMap);
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);

    quota = new SpaceUsedQuota();
  }

  @Test
  void usingLessThanTheLimit() {
    when(metrics.getTotalSize()).thenReturn(5L);

    assertFalse(quota.check(blobStore).isViolation(), STR."Quota should not be violated when using \{metrics.getTotalSize()} bytes with limit of 10");
  }

  @Test
  void usingMoreThanTheLimit() {
    when(metrics.getTotalSize()).thenReturn(20L);

    assertTrue(quota.check(blobStore).isViolation(), STR."Quota should be violated when using \{metrics.getTotalSize()} bytes with limit of 10");
  }

  @Test
  void greaterThanZeroLimitIsValid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);
    quota.validateConfig(config);
  }

  @Test
  void zeroLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(0);
    
    ValidationErrorsException exception = assertThrows(ValidationErrorsException.class, () -> {
      quota.validateConfig(config);
    }, STR."Should throw ValidationErrorsException for zero limit");
  }

  @Test
  void noLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(null);
    
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      quota.validateConfig(config);
    }, STR."Should throw IllegalArgumentException for null limit");
  }

  @Test
  @VirtualThreadTestGroup
  void concurrentQuotaChecksAreAccurate() throws Exception {
    // Set up a scenario where the quota is just below the limit
    when(metrics.getTotalSize()).thenReturn(9L);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger violationCount = new AtomicInteger(0);
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            if (quota.check(blobStore).isViolation()) {
              violationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify results - no violations should occur as we're under the limit
      assertFalse(violationCount.get() > 0, STR."Expected no violations but got \{violationCount.get()}");
      
      // Now set the metrics to exceed the limit
      when(metrics.getTotalSize()).thenReturn(11L);
      
      // Reset counters
      latch = new CountDownLatch(taskCount);
      violationCount.set(0);
      
      // Run the test again with the new limit
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            if (quota.check(blobStore).isViolation()) {
              violationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Verify results - all checks should report violations
      assertTrue(violationCount.get() == taskCount, 
          STR."Expected \{taskCount} violations but got \{violationCount.get()}");
    }
  }
  
  @Test
  void validateConfigWithPatternMatching() {
    // Test pattern matching for instanceof checks
    Object value = 15L;
    
    if (value instanceof Number number) {
      if (number instanceof Long longValue) {
        assertTrue(longValue > 0, STR."Limit value \{longValue} should be greater than zero");
      } else if (number instanceof Integer intValue) {
        assertTrue(intValue > 0, STR."Limit value \{intValue} should be greater than zero");
      }
    }
  }
}