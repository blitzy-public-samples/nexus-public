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
package org.sonatype.nexus.common.hash;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.sonatype.nexus.common.thread.Java21TestGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link MultiHashingInputStreamFactory}.
 * 
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class MultiHashingInputStreamFactoryTest
{
  @Before
  public void teardown() {
    MultiHashingInputStreamFactory.enableParallel();
    MultiHashingInputStreamFactory.setThreshold(-1);
  }

  @Test
  public void testDisableParallel() {
    MultiHashingInputStreamFactory.disableParallel();

    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass(),
        is(MultiHashingInputStream.class));
  }

  @Test
  public void testEnableParallel() {
    MultiHashingInputStreamFactory.disableParallel();
    MultiHashingInputStreamFactory.enableParallel();

    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass(),
        is(ParallelMultiHashingInputStream.class));
  }

  @Test
  public void testThreshold() {
    // default value should result in a parallel
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass(),
        is(ParallelMultiHashingInputStream.class));

    // White box - we know the threshold is multiplied by parallism resulting in zero, and zero isn't less than the
    // expected zero queued tasks (or if the JVM is using the pool a positive number)
    MultiHashingInputStreamFactory.setThreshold(0);

    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass(),
        is(MultiHashingInputStream.class));
  }
  
  /**
   * Tests that the factory correctly selects the implementation based on the current configuration.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testImplementationSelection() {
    // Test with parallel enabled (default)
    MultiHashingInputStreamFactory.enableParallel();
    MultiHashingInputStreamFactory.setThreshold(-1);
    
    MultiHashingInputStream stream = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
    assertThat(stream, instanceOf(ParallelMultiHashingInputStream.class));
    
    // Test with parallel disabled
    MultiHashingInputStreamFactory.disableParallel();
    
    stream = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
    assertThat(stream, instanceOf(MultiHashingInputStream.class));
  }
  
  /**
   * Tests that the factory works correctly with larger data streams when using Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testWithLargerDataStream() throws IOException {
    // Create a larger byte array to simulate a more realistic data stream
    byte[] data = new byte[1024 * 1024]; // 1MB
    Arrays.fill(data, (byte) 42);
    
    List<HashAlgorithm> algorithms = Arrays.asList(HashAlgorithm.SHA1, HashAlgorithm.MD5);
    
    // Test with parallel enabled
    MultiHashingInputStreamFactory.enableParallel();
    MultiHashingInputStreamFactory.setThreshold(-1);
    
    try (InputStream inputStream = new ByteArrayInputStream(data)) {
      MultiHashingInputStream stream = MultiHashingInputStreamFactory.input(algorithms, inputStream);
      assertThat(stream, instanceOf(ParallelMultiHashingInputStream.class));
      
      // Read all data to ensure hashing completes
      byte[] buffer = new byte[8192];
      while (stream.read(buffer) != -1) {
        // Just consume the data
      }
      
      // Verify we have hash results
      assertThat(stream.hashes().size(), is(algorithms.size()));
    }
  }
  
  /**
   * Tests that the factory correctly handles threshold settings with Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testThresholdWithVirtualThreads() {
    // Enable parallel processing
    MultiHashingInputStreamFactory.enableParallel();
    
    // Test with different threshold values
    MultiHashingInputStreamFactory.setThreshold(-1); // No limit
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()),
        instanceOf(ParallelMultiHashingInputStream.class));
    
    MultiHashingInputStreamFactory.setThreshold(0); // Should disable parallel
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()),
        instanceOf(MultiHashingInputStream.class));
    
    MultiHashingInputStreamFactory.setThreshold(100); // High threshold
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()),
        instanceOf(ParallelMultiHashingInputStream.class));
  }

  private static ByteArrayInputStream in() {
    return new ByteArrayInputStream(new byte[0]);
  }
}
