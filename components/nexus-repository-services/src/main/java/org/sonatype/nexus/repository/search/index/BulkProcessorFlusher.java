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

import java.util.concurrent.Future;

import org.sonatype.goodies.common.ComponentSupport;

import org.elasticsearch.action.bulk.BulkProcessor;

/**
 * A service for flushing a {@link BulkProcessor} using Virtual Threads.
 *
 * The intention is to leverage Java 21 Virtual Threads to efficiently handle I/O-bound operations
 * like flushing the BulkProcessor without blocking platform threads. This implementation creates
 * a dedicated virtual thread for each flush operation, which is more efficient for I/O operations
 * than using traditional thread pools.
 *
 * This class works in conjunction with {@link BulkProcessorUpdater} and is used by
 * {@link ElasticSearchIndexServiceImpl} to manage index flushing operations.
 *
 * @since 3.22
 */
public class BulkProcessorFlusher
    extends ComponentSupport
{
  private final BulkProcessor bulkProcessor;

  public BulkProcessorFlusher(final BulkProcessor bulkProcessor) {
    this.bulkProcessor = bulkProcessor;
  }

  /**
   * Flushes the BulkProcessor using a Virtual Thread.
   * 
   * @return A Future representing the completion of the flush operation
   */
  public Future<Void> flush() {
    return Thread.startVirtualThread(() -> {
      log.debug(STR."Trying to flush indexes for BulkProcessor \{System.identityHashCode(bulkProcessor)}...");
      try {
        bulkProcessor.flush();
        log.debug(STR."Successfully flushed indexes for BulkProcessor \{System.identityHashCode(bulkProcessor)}");
      } catch (Exception e) {
        log.error(STR."Error flushing indexes for BulkProcessor \{System.identityHashCode(bulkProcessor)}: \{e.getMessage()}", e);
        throw e;
      }
    });
  }
}
