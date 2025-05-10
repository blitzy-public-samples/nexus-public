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

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class BulkProcessorFlusherTest
    extends TestSupport
{

  @Mock
  private BulkProcessor bulkProcessor;

  @InjectMocks
  private BulkProcessorFlusher underTest;

  @Test
  public void runShouldFlushBulkProcessor() {
    underTest.call();

    verify(bulkProcessor).flush();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void runShouldFlushBulkProcessorInVirtualThread() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the BulkProcessorFlusher to run in a virtual thread
      Future<?> future = executor.submit(underTest);
      
      // Wait for completion
      future.get(5, TimeUnit.SECONDS);
      
      // Verify that flush was called
      verify(bulkProcessor).flush();
    }
  }
}