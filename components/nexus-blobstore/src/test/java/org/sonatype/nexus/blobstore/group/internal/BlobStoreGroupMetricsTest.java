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
package org.sonatype.nexus.blobstore.group.internal;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class BlobStoreGroupMetricsTest
    extends TestSupport
{

  @Test
  public void emptyMetricsIsAvailable() {
    assertThat(new BlobStoreGroupMetrics(emptyList()).isUnavailable(), is(false));
  }

  @Test
  public void metricsWithNoAvailableMemberIsUnavailable() {
    BlobStoreMetrics blobStoreMetrics = mock(BlobStoreMetrics.class);
    BlobStoreMetrics otherBlobStoreMetrics = mock(BlobStoreMetrics.class);
    when(blobStoreMetrics.isUnavailable()).thenReturn(true);
    when(otherBlobStoreMetrics.isUnavailable()).thenReturn(true);
    BlobStoreGroupMetrics groupMetrics =
        new BlobStoreGroupMetrics(Arrays.asList(blobStoreMetrics, otherBlobStoreMetrics));
    verify(blobStoreMetrics).isUnavailable();
    verify(blobStoreMetrics).getAvailableSpaceByFileStore();
    verify(otherBlobStoreMetrics).isUnavailable();
    verify(otherBlobStoreMetrics).getAvailableSpaceByFileStore();
    assertThat(groupMetrics.isUnavailable(), is(true));
  }

  @Test
  public void metricsWithOneAvailableMemberIsAvailable() {
    BlobStoreMetrics blobStoreMetrics = mock(BlobStoreMetrics.class);
    when(blobStoreMetrics.isUnavailable()).thenReturn(true);
    BlobStoreMetrics otherBlobStoreMetrics = mock(BlobStoreMetrics.class);
    BlobStoreGroupMetrics groupMetrics =
        new BlobStoreGroupMetrics(Arrays.asList(blobStoreMetrics, otherBlobStoreMetrics));
    verify(blobStoreMetrics).isUnavailable();
    verify(blobStoreMetrics).getAvailableSpaceByFileStore();
    verify(otherBlobStoreMetrics).isUnavailable();
    verify(otherBlobStoreMetrics).getAvailableSpaceByFileStore();
    assertThat(groupMetrics.isUnavailable(), is(false));
  }
  
  @Test
  public void concurrentMetricsAggregationWithVirtualThreads() throws Exception {
    // Create a large number of mock metrics to simulate high concurrency
    int metricCount = 1000;
    List<BlobStoreMetrics> metricsList = new ArrayList<>(metricCount);
    
    // Create metrics with alternating availability
    for (int i = 0; i < metricCount; i++) {
      BlobStoreMetrics metrics = mock(BlobStoreMetrics.class);
      when(metrics.isUnavailable()).thenReturn(i % 2 == 0); // even indices are unavailable
      metricsList.add(metrics);
    }
    
    // Create a group metrics instance with all the mock metrics
    BlobStoreGroupMetrics groupMetrics = new BlobStoreGroupMetrics(metricsList);
    
    // Verify the group is available (since we have odd-indexed available metrics)
    assertThat(groupMetrics.isUnavailable(), is(false));
    
    // Now test concurrent access using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Verify the metrics in each virtual thread
            assertThat(groupMetrics.isUnavailable(), is(false));
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat(errorCount.get(), is(0));
    }
    
    // Verify each mock was called at least once
    for (BlobStoreMetrics metrics : metricsList) {
      verify(metrics).isUnavailable();
      verify(metrics).getAvailableSpaceByFileStore();
    }
  }
}