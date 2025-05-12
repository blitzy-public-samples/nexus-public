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
package org.sonatype.nexus.testsuite.testsupport.utility;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.search.index.ElasticSearchIndexService;
import org.sonatype.nexus.repository.search.query.ElasticSearchQueryService;

import org.awaitility.Awaitility;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Helper class for search-related testing operations.
 *
 * @deprecated Please use {@code SearchTestSystem} instead. This class is maintained for backward compatibility
 * and has been updated for Java 21 compatibility.
 */
@Named
@Singleton
@Deprecated
public class SearchTestHelper
{
  @Inject
  public ElasticSearchIndexService indexService;

  @Inject
  public ElasticSearchQueryService elasticSearchQueryService;

  @Inject
  public EventManager eventManager;

  /**
   * Waits for indexing to finish and makes sure any updates are available to search.
   *
   * General flow is component/asset events -> bulk index requests -> search indexing.
   *
   * This implementation uses Java 21's Virtual Threads for improved efficiency when waiting for
   * asynchronous operations to complete.
   */
  public void waitForSearch() {
    // Use CompletableFuture with virtual threads for non-blocking wait
    CompletableFuture.runAsync(() -> {
      Awaitility.await().atMost(30, SECONDS).until(eventManager::isCalmPeriod);
      indexService.flush(false); // no need for full fsync here
      Awaitility.await().atMost(30, SECONDS).until(indexService::isCalmPeriod);
    }, Executors.newVirtualThreadPerTaskExecutor()).join();
  }

  /**
   * Verifies if a component exists in the repository.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param repository the repository to search in
   * @param name the name of the component
   * @param version the version of the component
   * @param exists whether the component is expected to exist
   * @throws Exception if an error occurs during verification
   */
  public void verifyComponentExists(
      final WebTarget nexusSearchWebTarget,
      final Repository repository,
      final String name,
      final String version,
      final boolean exists) throws Exception
  {
    String repositoryName = repository.getName();
    List<Map<String, Object>> items = searchForComponent(nexusSearchWebTarget, repositoryName, name, version);
    assertThat(STR."Component \{name}:\{version} existence check", items.size(), is(exists ? 1 : 0));
  }

  /**
   * Returns the ElasticSearchQueryService instance.
   *
   * @return the ElasticSearchQueryService
   */
  public ElasticSearchQueryService queryService() {
    return elasticSearchQueryService;
  }

  /**
   * Searches for a component in the specified repository.
   *
   * @param nexusSearchUrl the web target for search requests
   * @param repository the repository to search in
   * @param artifactId the artifactId of the component
   * @param version the version of the component
   * @return a list of matching components
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> searchForComponent(
      final WebTarget nexusSearchUrl, final String repository,
      final String artifactId,
      final String version)
  {
    waitForSearch();

    Response response = nexusSearchUrl
        .queryParam("repository", repository)
        .queryParam("maven.artifactId", artifactId)
        .queryParam("maven.baseVersion", version)
        .request()
        .buildGet()
        .invoke();

    Map<String, Object> map = response.readEntity(Map.class);
    return (List<Map<String, Object>>) map.get("items");
  }

  /**
   * Searches for components with the specified tag in the repository.
   *
   * @param nexusSearchUrl the web target for search requests
   * @param repository the repository to search in
   * @param tag the tag to search for
   * @return a list of matching components
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> searchByTag(
      final WebTarget nexusSearchUrl,
      final String repository,
      final String tag)
  {
    waitForSearch();

    Response response = nexusSearchUrl
        .queryParam("repository", repository)
        .queryParam("tag", tag)
        .request()
        .buildGet()
        .invoke();

    Map<String, Object> map = response.readEntity(Map.class);
    return (List<Map<String, Object>>) map.get("items");
  }
}