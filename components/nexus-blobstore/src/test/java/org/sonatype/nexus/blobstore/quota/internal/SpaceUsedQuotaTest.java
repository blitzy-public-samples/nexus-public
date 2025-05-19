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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.lang.StringTemplate.STR;
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
  public void setup() {
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

    assertFalse(quota.check(blobStore).isViolation(), STR."Expected no violation when using less than the limit");
  }

  @Test
  void usingMoreThanTheLimit() {
    when(metrics.getTotalSize()).thenReturn(20L);

    assertTrue(quota.check(blobStore).isViolation(), STR."Expected violation when using more than the limit");
  }

  @Test
  void greaterThanZeroLimitIsValid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(10L);
    quota.validateConfig(config);
  }

  @Test
  void zeroLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(0);
    assertThrows(ValidationErrorsException.class, () -> quota.validateConfig(config));
  }

  @Test
  void noLimitIsInvalid() {
    when(attributesMap.get(eq(LIMIT_KEY), eq(Number.class))).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> quota.validateConfig(config));
  }
  
  @Test
  @VirtualThreadTestGroup
  void concurrentQuotaChecksAreConsistent() throws Exception {
    when(metrics.getTotalSize()).thenReturn(20L); // Over the limit
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger violationCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
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
      latch.await(30, TimeUnit.SECONDS);
      
      // All checks should report a violation since we're over the limit
      assertTrue(violationCount.get() == taskCount, 
          STR."Expected all \{taskCount} quota checks to report violations, but got \{violationCount.get()}");
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  @VirtualThreadTestGroup
  void quotaCalculationsRemainAccurateUnderConcurrentOperations() throws Exception {
    // Start with a size under the limit
    when(metrics.getTotalSize()).thenReturn(5L);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger initialViolationCount = new AtomicInteger(0);
    AtomicInteger finalViolationCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // First phase: check quota while under the limit
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            if (quota.check(blobStore).isViolation()) {
              initialViolationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
      
      // Change the metrics to be over the limit
      when(metrics.getTotalSize()).thenReturn(15L);
      
      // Reset for second phase
      latch = new CountDownLatch(taskCount);
      
      // Second phase: check quota while over the limit
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            if (quota.check(blobStore).isViolation()) {
              finalViolationCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue(initialViolationCount.get() == 0, 
          STR."Expected no violations initially, but got \{initialViolationCount.get()}");
      assertTrue(finalViolationCount.get() == taskCount, 
          STR."Expected all \{taskCount} quota checks to report violations after size increase, but got \{finalViolationCount.get()}");
    } finally {
      executor.shutdown();
    }
  }
}