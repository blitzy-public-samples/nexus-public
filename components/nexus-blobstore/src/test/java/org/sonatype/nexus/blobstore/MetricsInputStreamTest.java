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
import java.security.SecureRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.google.common.io.ByteStreams.copy;
import static com.google.common.io.ByteStreams.nullOutputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link MetricsInputStream}.
 */
public class MetricsInputStreamTest
{
  @Test
  public void should_return_correct_length() throws Exception {
    assertEquals(3L, measure("ABC".getBytes("UTF-8")).getSize());
    assertEquals(10000L, measure(new byte[10000]).getSize());
  }

  @Test
  public void should_generate_different_hashes_for_different_content() throws Exception {
    final String hash1 = measure("ABC".getBytes("UTF-8")).getMessageDigest();
    final String hash2 = measure(new byte[10000]).getMessageDigest();

    assertNotEquals(hash1, hash2);
  }

  @Test
  public void should_match_reference_hash() throws Exception {
    final MetricsInputStream measure = measure(
        getClass().getResourceAsStream("sha1_is_2589766c6dac3402cab552602d457e7e8af12efd.bytes"));
    assertEquals("2589766c6dac3402cab552602d457e7e8af12efd", measure.getMessageDigest());
  }
  
  @Test
  public void should_handle_large_data_stream() throws Exception {
    // Create a 5MB array
    byte[] largeData = new byte[5 * 1024 * 1024];
    // Fill with random data to ensure unique hash
    new SecureRandom().nextBytes(largeData);
    
    MetricsInputStream metrics = measure(largeData);
    assertEquals(5 * 1024 * 1024, metrics.getSize());
    // Just verify we get a non-empty hash
    Assertions.assertNotNull(metrics.getMessageDigest());
    Assertions.assertFalse(metrics.getMessageDigest().isEmpty());
  }
  
  @Test
  public void should_calculate_metrics_for_various_sized_streams() throws Exception {
    // Test with different sizes to verify accuracy across range
    int[] testSizes = {1024, 64 * 1024, 256 * 1024, 1024 * 1024};
    
    for (int size : testSizes) {
      byte[] data = new byte[size];
      new SecureRandom().nextBytes(data);
      
      MetricsInputStream metrics = measure(data);
      assertEquals(size, metrics.getSize(), 
          "Size measurement should be accurate for " + size + " bytes");
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
