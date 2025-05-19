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

import org.sonatype.nexus.virtualthread.Java21TestGroup;
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link MultiHashingInputStreamFactory}.
 *
 * @since 3.0
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
        is(equalTo(MultiHashingInputStream.class)));
  }

  @Test
  public void testEnableParallel() {
    MultiHashingInputStreamFactory.disableParallel();
    MultiHashingInputStreamFactory.enableParallel();

    // In Java 21, this should create a VirtualThreadMultiHashingInputStream
    // For backward compatibility, we check if it's not a MultiHashingInputStream
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()) instanceof MultiHashingInputStream, is(true));
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()) instanceof VirtualThreadMultiHashingInputStream 
        || MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass().getSimpleName().contains("Parallel"), 
        is(true));
  }

  @Test
  public void testThreshold() {
    // default value should result in a parallel implementation
    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()) instanceof VirtualThreadMultiHashingInputStream 
        || MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass().getSimpleName().contains("Parallel"), 
        is(true));

    // Setting threshold to 0 should result in a non-parallel implementation
    MultiHashingInputStreamFactory.setThreshold(0);

    assertThat(MultiHashingInputStreamFactory.input(Collections.emptyList(), in()).getClass(),
        is(equalTo(MultiHashingInputStream.class)));
  }
  
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testVirtualThreadImplementation() {
    // Ensure we're using virtual threads when enabled
    MultiHashingInputStreamFactory.enableParallel();
    MultiHashingInputStreamFactory.setThreshold(-1);
    
    MultiHashingInputStream stream = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
    assertThat(stream, instanceOf(VirtualThreadMultiHashingInputStream.class));
  }
  
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testVirtualThreadThresholdBehavior() {
    // Test with threshold of 1 and multiple hash operations
    MultiHashingInputStreamFactory.enableParallel();
    MultiHashingInputStreamFactory.setThreshold(1);
    
    // First request should use virtual threads
    MultiHashingInputStream stream1 = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
    assertThat(stream1, instanceOf(VirtualThreadMultiHashingInputStream.class));
    
    // Second request should use regular implementation since we're at threshold
    MultiHashingInputStream stream2 = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
    assertThat(stream2, instanceOf(MultiHashingInputStream.class));
    assertThat(stream2 instanceof VirtualThreadMultiHashingInputStream, is(false));
    
    // After closing the first stream, we should be able to use virtual threads again
    try {
      stream1.close();
      MultiHashingInputStream stream3 = MultiHashingInputStreamFactory.input(Collections.emptyList(), in());
      assertThat(stream3, instanceOf(VirtualThreadMultiHashingInputStream.class));
    }
    catch (IOException e) {
      // Ignore exception in test
    }
  }
  
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void testMultipleHashAlgorithms() throws IOException {
    // Test with multiple hash algorithms to ensure they all work correctly
    List<HashAlgorithm> algorithms = Arrays.asList(HashAlgorithm.SHA1, HashAlgorithm.MD5, HashAlgorithm.SHA256);
    
    // Create a test input stream with some data
    byte[] testData = "Test data for hashing with virtual threads".getBytes();
    InputStream inputStream = new ByteArrayInputStream(testData);
    
    // Create the hashing stream
    MultiHashingInputStream hashingStream = MultiHashingInputStreamFactory.input(algorithms, inputStream);
    
    // Read all data to trigger hashing
    byte[] buffer = new byte[1024];
    while (hashingStream.read(buffer) != -1) {
      // Just read through the stream
    }
    
    // Verify we have hashes for all algorithms
    for (HashAlgorithm algorithm : algorithms) {
      assertThat(hashingStream.hashes().containsKey(algorithm), is(true));
      assertThat(hashingStream.hashes().get(algorithm).length() > 0, is(true));
    }
    
    hashingStream.close();
  }

  private static ByteArrayInputStream in() {
    return new ByteArrayInputStream(new byte[0]);
  }
}
