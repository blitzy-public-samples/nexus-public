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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;

import static com.google.common.io.ByteStreams.copy;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Tests for {@link MetricsInputStream}.
 */
public class MetricsInputStreamTest
    extends TestSupport
{
  @Test
  void testLength() throws Exception {
    assertThat(measure("ABC".getBytes("UTF-8")).getSize(), is(equalTo(3L)));
    assertThat(measure(new byte[10000]).getSize(), is(equalTo(10000L)));
  }

  @Test
  void testHashesDiffer() throws Exception {
    final String hash1 = measure("ABC".getBytes("UTF-8")).getMessageDigest();
    final String hash2 = measure(new byte[10000]).getMessageDigest();

    assertThat(hash1, not(equalTo(hash2)));
  }

  @Test
  void referenceHashMatches() throws Exception {
    final MetricsInputStream measure = measure(
        getClass().getResourceAsStream("sha1_is_2589766c6dac3402cab552602d457e7e8af12efd.bytes"));
    assertThat(measure.getMessageDigest(), is(equalTo("2589766c6dac3402cab552602d457e7e8af12efd")));
  }

  @Test
  @VirtualThreadTestGroup
  void metricsCollectionInVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<MetricsInputStream> future = executor.submit(() -> {
        byte[] testData = STR."Virtual Thread Test Data \{System.currentTimeMillis()}".getBytes("UTF-8");
        return measure(new ByteArrayInputStream(testData));
      });
      
      MetricsInputStream result = future.get(5, TimeUnit.SECONDS);
      
      // Verify metrics were collected correctly in the virtual thread
      assertThat(result.getSize() > 0, is(true));
      assertThat(result.getMessageDigest().length(), is(equalTo(40)));
      
      log.info(STR."Virtual thread metrics collection successful: size=\{result.getSize()}, hash=\{result.getMessageDigest()}");
    }
  }

  @Test
  @VirtualThreadTestGroup
  void parallelStreamMetricsCollection() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple virtual threads to process metrics in parallel
      int threadCount = 100;
      
      // Use pattern matching with instanceof to verify results
      var results = IntStream.range(0, threadCount)
          .parallel()
          .mapToObj(i -> {
            try {
              byte[] data = STR."Test data for thread \{i}".getBytes("UTF-8");
              return executor.submit(() -> measure(new ByteArrayInputStream(data)));
            } catch (Exception e) {
              log.error(STR."Error creating task for thread \{i}", e);
              return null;
            }
          })
          .map(future -> {
            try {
              return future != null ? future.get(1, TimeUnit.SECONDS) : null;
            } catch (Exception e) {
              log.error("Error getting future result", e);
              return null;
            }
          })
          .toList();
      
      // Verify all metrics were collected successfully using pattern matching
      for (var result : results) {
        if (result instanceof MetricsInputStream metrics) {
          assertThat(metrics.getSize() > 0, is(true));
          assertThat(metrics.getMessageDigest().length(), is(equalTo(40)));
        } else {
          throw new AssertionError(STR."Expected MetricsInputStream but got \{result}");
        }
      }
      
      log.info(STR."Successfully processed metrics for \{results.size()} virtual threads");
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