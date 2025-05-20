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

import java.util.concurrent.ExecutionException;

import org.sonatype.goodies.common.ComponentSupport;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;

/**
 * A processor for adding an {@link IndexRequest} or {@link DeleteRequest} to a {@link BulkProcessor}
 * using Java 21 Virtual Threads for improved I/O performance.
 *
 * This class leverages Virtual Threads to efficiently process ActionRequests without blocking platform threads.
 * Each request is processed in its own Virtual Thread, allowing for high concurrency with minimal resource usage.
 * 
 * The BulkProcessor's mutex is still respected as the actual add operation happens within the Virtual Thread,
 * ensuring thread safety while maximizing throughput for I/O-bound operations.
 *
 * @since 3.22
 */
public class BulkProcessorUpdater<T extends ActionRequest<T>>
    extends ComponentSupport
{
  private final BulkProcessor bulkProcessor;

  private final T actionRequest;

  /**
   * Constructs a new BulkProcessorUpdater.
   *
   * @param bulkProcessor the BulkProcessor to add requests to
   * @param actionRequest the ActionRequest to process (IndexRequest or DeleteRequest)
   */
  public BulkProcessorUpdater(final BulkProcessor bulkProcessor, final T actionRequest) {
    this.bulkProcessor = bulkProcessor;
    this.actionRequest = actionRequest;
  }

  /**
   * Processes the ActionRequest by starting a Virtual Thread to add it to the BulkProcessor.
   * This method leverages Java 21 Virtual Threads for efficient I/O operations.
   *
   * @return the Thread that was started to process the request
   */
  public Thread process() {
    return Thread.startVirtualThread(() -> {
      try {
        processRequest();
      }
      catch (Exception e) {
        handleProcessingError(e);
      }
    });
  }

  /**
   * Processes the ActionRequest based on its type using pattern matching.
   * This provides type-safe handling of different request types.
   */
  private void processRequest() {
    log.debug(STR."Processing \{getRequestTypeName()} request in virtual thread \{Thread.currentThread().getName()}");
    
    // Add the request to the BulkProcessor
    bulkProcessor.add(actionRequest);
    
    log.debug(STR."Successfully added \{getRequestTypeName()} request to batch");
  }

  /**
   * Determines the request type name using pattern matching for switch.
   * This demonstrates Java 21's pattern matching capabilities for more concise type checking.
   *
   * @return a descriptive name of the request type
   */
  private String getRequestTypeName() {
    return switch (actionRequest) {
      case IndexRequest i -> "index";
      case DeleteRequest d -> "delete";
      default -> actionRequest.getClass().getSimpleName();
    };
  }

  /**
   * Handles any errors that occur during request processing with enhanced context.
   *
   * @param e the exception that occurred
   */
  private void handleProcessingError(final Exception e) {
    String requestType = getRequestTypeName();
    String threadName = Thread.currentThread().getName();
    
    log.error(STR."Error processing \{requestType} request in virtual thread \{threadName}: \{e.getMessage()}", e);
    
    // Additional error handling could be added here, such as metrics tracking or retry logic
  }
}