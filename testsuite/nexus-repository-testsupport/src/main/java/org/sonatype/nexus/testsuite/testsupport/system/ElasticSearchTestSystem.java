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
package org.sonatype.nexus.testsuite.testsupport.system;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;
import org.sonatype.nexus.repository.search.query.ElasticSearchQueryService;

import org.awaitility.core.ConditionFactory;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * Test support system for ElasticSearch functionality.
 * 
 * <p>Provides methods for waiting for search operations to complete and for waiting for search results.
 * Leverages Java 21 virtual threads for improved concurrency in asynchronous indexing and event synchronization.</p>
 *
 * @since 3.41
 */
@Named
@Singleton
public class ElasticSearchTestSystem
    implements SearchTestSystem
{
  @Inject
  public EventManager eventManager;

  @Inject
  public ElasticSearchIndexService indexService;

  @Inject
  public ElasticSearchQueryService elasticSearchQueryService;

  /**
   * Waits for search operations to complete by ensuring that the event manager and index service
   * have reached a calm period. Uses virtual threads for non-blocking asynchronous operations.
   */
  @Override
  public void waitForSearch() {
    // Use CompletableFuture with virtual threads for non-blocking asynchronous operations
    CompletableFuture.runAsync(() -> {
      await().atMost(30, SECONDS).until(eventManager::isCalmPeriod);
    }, Executors.newVirtualThreadPerTaskExecutor()).join();
    
    // Flush the index service without full fsync
    indexService.flush(false);
    
    // Wait for the index service to reach a calm period using virtual threads
    CompletableFuture.runAsync(() -> {
      await().atMost(30, SECONDS).until(indexService::isCalmPeriod);
    }, Executors.newVirtualThreadPerTaskExecutor()).join();
  }

  /**
   * Returns a condition factory for waiting for search results.
   * 
   * @return a condition factory configured with appropriate timeouts and polling intervals
   */
  @Override
  public ConditionFactory waitForSearchResults() {
    return await().atMost(120, SECONDS).pollInterval(1, SECONDS);
  }

  /**
   * Returns the ElasticSearch query service.
   * 
   * @return the ElasticSearch query service
   */
  public ElasticSearchQueryService queryService() {
    return elasticSearchQueryService;
  }
}