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

import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.common.ComponentSupport;

import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;

/**
 * {@link BulkProcessor.Listener} that logs and tracks inflight requests.
 * Optimized for Java 21 Virtual Threads environment.
 */
class BulkIndexUpdateListener
    extends ComponentSupport
    implements BulkProcessor.Listener
{
  private final AtomicInteger inflightRequestCount = new AtomicInteger();

  /**
   * Returns the current count of inflight requests.
   *
   * @return the current inflight request count
   */
  int inflightRequestCount() {
    return inflightRequestCount.get();
  }

  @Override
  public void beforeBulk(final long executionId, final BulkRequest request) {
    // Using addAndGet instead of getAndAdd for better performance in Virtual Thread environments
    int newCount = inflightRequestCount.addAndGet(request.numberOfActions());

    if (log.isDebugEnabled()) {
      log.debug(STR."index update starting, executionId: \{executionId}, request count: \{request.numberOfActions()}, "
          + STR."request size (bytes): \{request.estimatedSizeInBytes()}, current inflight: \{newCount}");
    }
  }

  @Override
  public void afterBulk(final long executionId, final BulkRequest request, final BulkResponse response) {
    // Using addAndGet instead of getAndAdd for better performance in Virtual Thread environments
    int newCount = inflightRequestCount.addAndGet(-request.numberOfActions());

    if (log.isDebugEnabled()) {
      log.debug(STR."index update success, executionId: \{executionId}, request count: \{request.numberOfActions()}, "
          + STR."request size (bytes): \{request.estimatedSizeInBytes()}, response took: \{response.getTook()}, "
          + STR."response hasFailures: \{response.hasFailures()}, remaining inflight: \{newCount}");
    }
  }

  @Override
  public void afterBulk(final long executionId, final BulkRequest request, final Throwable failure) {
    // Using updateAndGet for atomic update with better Virtual Thread performance
    int newCount = inflightRequestCount.updateAndGet(current -> current - request.numberOfActions());

    // Enhanced error logging with more diagnostic information for Virtual Thread environments
    log.error(STR."index update failure, executionId: \{executionId}, request count: \{request.numberOfActions()}, "
        + STR."request size (bytes): \{request.estimatedSizeInBytes()}, remaining inflight: \{newCount}, "
        + STR."thread name: \{Thread.currentThread().getName()}, thread id: \{Thread.currentThread().threadId()}; "
        + "this may indicate that not enough CPU is available to effectively index repository content or "
        + "a Virtual Thread scheduling issue has occurred", 
        failure);
  }
}