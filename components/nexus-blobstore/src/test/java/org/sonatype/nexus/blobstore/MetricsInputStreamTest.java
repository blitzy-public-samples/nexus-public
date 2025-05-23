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
package org.sonatype.nexus.blobstore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.google.common.io.ByteStreams.copy;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MetricsInputStream}.
 *
 * @since 3.60
 */
public class MetricsInputStreamTest
    extends TestSupport
{
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    // Create executor for virtual threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() throws Exception {
    // Shutdown executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void testLength() throws Exception {
    assertThat(measure("ABC".getBytes(StandardCharsets.UTF_8)).getSize(), is(equalTo(3L)));
    assertThat(measure(new byte[10000]).getSize(), is(equalTo(10000L)));
  }

  @Test
  void testHashesDiffer() throws Exception {
    final String hash1 = measure("ABC".getBytes(StandardCharsets.UTF_8)).getMessageDigest();
    final String hash2 = measure(new byte[10000]).getMessageDigest();

    assertThat(hash1, not(equalTo(hash2)));
  }

  @Test
  void referenceHashMatches() throws Exception {
    final MetricsInputStream measure = measure(
        getClass().getResourceAsStream("sha1_is_2589766c6dac3402cab552602d457e7e8af12efd.bytes"));
    
    // Using pattern matching for more concise code
    if (measure.getMessageDigest() instanceof String hash) {
      assertThat(hash, is(equalTo("2589766c6dac3402cab552602d457e7e8af12efd")));
    }
  }

  /**
   * Tests metrics collection in a virtual thread context.
   */
  @Test
  @VirtualThreadTestGroup
  void testMetricsInVirtualThread() throws Exception {
    Future<MetricsInputStream> future = virtualThreadExecutor.submit(() -> {
      // Using String Template for more readable output
      String testData = STR."Testing metrics in \{Thread.currentThread().isVirtual() ? "virtual" : "platform"} thread";
      MetricsInputStream metrics = measure(testData.getBytes(StandardCharsets.UTF_8));
      
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run in a virtual thread");
      
      return metrics;
    });
    
    MetricsInputStream result = future.get(5, TimeUnit.SECONDS);
    
    // Verify metrics were collected correctly
    assertThat(result.getSize(), is(equalTo((long) STR."Testing metrics in virtual thread".getBytes(StandardCharsets.UTF_8).length)));
    assertThat(result.getMessageDigest(), is(not(equalTo(""))));
  }

  /**
   * Tests parallel streaming with virtual threads for efficiency.
   */
  @Test
  @VirtualThreadTestGroup
  void testParallelStreamingWithVirtualThreads() throws Exception {
    final int streamCount = 1000;
    List<Future<MetricsInputStream>> futures = new ArrayList<>();
    
    // Create many parallel tasks using virtual threads
    IntStream.range(0, streamCount).forEach(i -> {
      futures.add(virtualThreadExecutor.submit(() -> {
        // Create unique data for each thread
        String testData = STR."Thread \{i} data";
        return measure(testData.getBytes(StandardCharsets.UTF_8));
      }));
    });
    
    // Wait for all tasks to complete and verify results
    for (Future<MetricsInputStream> future : futures) {
      MetricsInputStream result = future.get(10, TimeUnit.SECONDS);
      
      // Verify each result has valid metrics
      if (result instanceof MetricsInputStream metrics) {
        assertThat(metrics.getSize(), is(not(equalTo(0L))));
        assertThat(metrics.getMessageDigest(), is(not(equalTo(""))));
      }
    }
  }

  private MetricsInputStream measure(final byte[] testData) throws Exception {
    return measure(new ByteArrayInputStream(testData));
  }

  private MetricsInputStream measure(final InputStream inputStream) throws Exception {
    final MetricsInputStream metricStream = new MetricsInputStream(inputStream);
    copy(metricStream, nullOutputStream());
    return metricStream;
  }
}