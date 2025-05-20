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
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.java21.Java21TestGroup;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class BulkProcessorFlusherTest
    extends TestSupport
{
  @Mock
  private BulkProcessor bulkProcessor;

  @InjectMocks
  private BulkProcessorFlusher underTest;

  @Test
  public void shouldFlushBulkProcessor() {
    underTest.call();

    verify(bulkProcessor).flush();
  }

  @Test
  public void shouldFlushBulkProcessorWithVirtualThread() throws Exception {
    // Create a latch to wait for the virtual thread to complete
    CountDownLatch latch = new CountDownLatch(1);
    
    // Use a virtual thread to execute the flush operation
    Thread.ofVirtual().start(() -> {
      try {
        underTest.call();
        latch.countDown();
      } catch (Exception e) {
        log.error("Error in virtual thread", e);
      }
    });
    
    // Wait for the virtual thread to complete (with timeout)
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the flush was called once
    verify(bulkProcessor, times(1)).flush();
  }
}