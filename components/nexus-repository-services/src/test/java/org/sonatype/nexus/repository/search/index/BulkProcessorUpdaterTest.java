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

import org.sonatype.goodies.testsupport.TestSupport;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.delete.DeleteRequest;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link BulkProcessorUpdater} with both platform and virtual threads.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21")
@Tag("VirtualThread")
public class BulkProcessorUpdaterTest
    extends TestSupport
{
  @Mock
  private BulkProcessor bulkProcessor;

  @Mock
  private ActionRequest<DeleteRequest> deleteRequest;

  @InjectMocks
  private BulkProcessorUpdater<DeleteRequest> underTest;

  @Test
  public void should_add_request_to_bulk_processor() {
    // When
    underTest.call();

    // Then
    verify(bulkProcessor, times(1)).add(deleteRequest);
  }

  @Test
  public void should_process_bulk_operations_with_virtual_threads() throws Exception {
    // Given
    int numOperations = 10;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // When - execute multiple bulk operations concurrently with virtual threads
      Future<?>[] futures = new Future<?>[numOperations];
      for (int i = 0; i < numOperations; i++) {
        futures[i] = executor.submit(underTest);
      }
      
      // Wait for all operations to complete
      for (Future<?> future : futures) {
        future.get();
      }
      
      // Then - verify the bulk processor was called the expected number of times
      verify(bulkProcessor, times(numOperations)).add(deleteRequest);
    } finally {
      executor.shutdown();
    }
  }
}