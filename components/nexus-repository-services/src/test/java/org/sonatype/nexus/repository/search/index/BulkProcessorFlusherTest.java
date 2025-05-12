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
package org.sonatype.nexus.repository.search.index;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class BulkProcessorFlusherTest
    extends TestSupport
{

  @Mock
  private BulkProcessor bulkProcessor;

  @InjectMocks
  private BulkProcessorFlusher underTest;

  @Test
  void runShouldFlushBulkProcessor() {
    underTest.call();

    verify(bulkProcessor).flush();
  }
  
  /**
   * Tests that BulkProcessorFlusher works correctly when executed in a virtual thread context.
   * This validates that the implementation is compatible with Java 21's virtual thread feature.
   */
  @Test
  @org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
  void virtualThreadExecutionShouldFlushBulkProcessor() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Use a latch to coordinate test completion
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean success = new AtomicBoolean(false);
    
    try {
      // Execute the BulkProcessorFlusher in a virtual thread
      executor.submit(() -> {
        try {
          // Execute the flusher in the virtual thread context
          underTest.call();
          success.set(true);
        } finally {
          latch.countDown();
        }
      });
      
      // Wait for the virtual thread to complete
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertThat("Virtual thread execution completed", completed, is(true));
      assertThat("BulkProcessorFlusher executed successfully", success.get(), is(true));
      verify(bulkProcessor, times(1)).flush();
    } finally {
      executor.shutdown();
    }
  }
}