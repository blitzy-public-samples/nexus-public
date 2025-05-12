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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import com.google.common.collect.Lists;
import org.awaitility.core.ConditionFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Interface for search testing functionality in Nexus Repository.
 * <p>
 * This interface provides methods for verifying search results and waiting for search indexing to complete.
 * Implementation leverages Java 21 virtual threads for improved concurrency in search operations.
 * <p>
 * Compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 */
public interface SearchTestSystem
{
  /**
   * Waits for indexing to finish and makes sure any updates are available to search.
   * <p>
   * General flow is component/asset events -> bulk index requests -> search indexing.
   * <p>
   * Implementation should leverage Java 21 virtual threads for improved concurrency.
   */
  void waitForSearch();

  /**
   * Create the {@link ConditionFactory} which is suitable for a search requests.
   * @return the {@link ConditionFactory} object.
   */
  ConditionFactory waitForSearchResults();

  /**
   * Executes a task using virtual threads for improved concurrency.
   * <p>
   * This method leverages Java 21 virtual threads to execute I/O-bound operations
   * more efficiently, allowing for higher concurrency with lower resource usage.
   *
   * @param <T> the type of result returned by the task
   * @param task the task to execute
   * @return the result of the task execution
   */
  default <T> T executeWithVirtualThreads(Supplier<T> task) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return CompletableFuture.supplyAsync(task, executor).join();
    }
  }

  /**
   * Verifies that a component exists in the repository with the specified GAV coordinates.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param repositoryName the name of the repository to search in
   * @param group the optional group identifier
   * @param name the optional artifact name
   * @param version the optional version string
   */
  default void verifyComponentExists(
      final WebTarget nexusSearchWebTarget,
      final String repositoryName,
      final Optional<String> group,
      final Optional<String> name,
      final Optional<String> version)
  {
    assertThat(verifyComponentExistsByGAV(nexusSearchWebTarget, repositoryName, group, name, version), is(1));
  }

  /**
   * Verifies that a component does not exist in the repository with the specified GAV coordinates.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param repositoryName the name of the repository to search in
   * @param group the optional group identifier
   * @param name the optional artifact name
   * @param version the optional version string
   */
  default void verifyComponentDoesNotExist(
      final WebTarget nexusSearchWebTarget,
      final String repositoryName,
      final Optional<String> group,
      final Optional<String> name,
      final Optional<String> version)
  {
    assertThat(verifyComponentExistsByGAV(nexusSearchWebTarget, repositoryName, group, name, version), is(0));
  }

  /**
   * Verifies that a component does not exist in the repository with the specified query parameter.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParam the query parameter to search with
   */
  default void verifyComponentDoesNotExist(final WebTarget nexusSearchWebTarget,
                                           final QueryParam queryParam){
    verifyComponentDoesNotExist(nexusSearchWebTarget, Lists.newArrayList(queryParam));
  }

  /**
   * Verifies that a component does not exist in the repository with the specified query parameters.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParams the collection of query parameters to search with
   */
  default void verifyComponentDoesNotExist(final WebTarget nexusSearchWebTarget,
                                           Collection<QueryParam> queryParams){
    List<Map<String, Object>> items = searchForComponentByParams(nexusSearchWebTarget, queryParams);
    assertThat(items.size(), is(0));
  }

  /**
   * Verifies that a component exists in the repository with the specified query parameter.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParam the query parameter to search with
   */
  default void verifyComponentExists(
      final WebTarget nexusSearchWebTarget,
      final QueryParam queryParam)
  {
    verifyComponentExists(nexusSearchWebTarget, Lists.newArrayList(queryParam));
  }

  /**
   * Verifies that a component exists in the repository with the specified query parameters.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParams the collection of query parameters to search with
   */
  default void verifyComponentExists(
      final WebTarget nexusSearchWebTarget,
      final Collection<QueryParam> queryParams)
  {
    List<Map<String, Object>> items = searchForComponentByParams(nexusSearchWebTarget, queryParams);
    assertThat(items.size(), is(1));
  }

  /**
   * Verifies the number of component appearances in the repository with the specified query parameter.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParam the query parameter to search with
   * @param numberOfAppearances the expected number of component appearances
   */
  default void verifyNumberOfComponentsAppearances(
      final WebTarget nexusSearchWebTarget,
      final QueryParam queryParam,
      final int numberOfAppearances)
  {
    verifyNumberOfComponentsAppearances(nexusSearchWebTarget, Lists.newArrayList(queryParam), numberOfAppearances);
  }

  /**
   * Verifies the number of component appearances in the repository with the specified query parameters.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param queryParams the collection of query parameters to search with
   * @param numberOfAppearances the expected number of component appearances
   */
  default void verifyNumberOfComponentsAppearances(
      final WebTarget nexusSearchWebTarget,
      final Collection<QueryParam> queryParams,
      final int numberOfAppearances)
  {
    List<Map<String, Object>> items = searchForComponentByParams(nexusSearchWebTarget, queryParams);
    assertThat(items.size(), is(numberOfAppearances));
  }

  /**
   * Verifies if a component exists in the repository with the specified GAV coordinates and returns the count.
   *
   * @param nexusSearchWebTarget the web target for search requests
   * @param repositoryName the name of the repository to search in
   * @param group the optional group identifier
   * @param name the optional artifact name
   * @param version the optional version string
   * @return the number of matching components found
   */
  default int verifyComponentExistsByGAV(
      final WebTarget nexusSearchWebTarget,
      final String repositoryName,
      final Optional<String> group,
      final Optional<String> name,
      final Optional<String> version)
  {
    List<QueryParam> queryParams = new ArrayList<>();

    queryParams.add(new QueryParam("repository", repositoryName));
    group.map(g -> new QueryParam("group", g)).ifPresent(queryParams::add);
    name.map(n -> new QueryParam("name", n)).ifPresent(queryParams::add);
    version.map(v -> new QueryParam("version", v)).ifPresent(queryParams::add);

    List<Map<String, Object>> items = searchForComponentByParams(nexusSearchWebTarget, queryParams);
    return items.size();
  }

  /**
   * Query parameter class for search operations.
   */
  class QueryParam
  {
    public final String name;
    public final String value;

    /**
     * Constructs a new query parameter with the specified name and value.
     *
     * @param name the parameter name
     * @param value the parameter value
     */
    public QueryParam(final String name, final String value) {
      this.name = name;
      this.value = value;
    }
  }

  /**
   * Searches for components using the specified parameters.
   * <p>
   * This method leverages Java 21 virtual threads for improved concurrency in search operations.
   *
   * @param nexusSearchUrl the web target for search requests
   * @param queryParams the collection of query parameters to search with
   * @return a list of component maps matching the search criteria
   */
  default List<Map<String, Object>> searchForComponentByParams(
      final WebTarget nexusSearchUrl,
      final Collection<QueryParam> queryParams)
  {
    waitForSearch();

    return executeWithVirtualThreads(() -> {
      WebTarget request = nexusSearchUrl;
      for (QueryParam param : queryParams) {
        request = request.queryParam(param.name, param.value);
      }

      Response response = request
          .request()
          .buildGet()
          .invoke();

      Map<String, Object> map = response.readEntity(Map.class);
      return (List<Map<String, Object>>) map.get("items");
    });
  }

  /**
   * Searches for components by tag.
   * <p>
   * This method leverages Java 21 virtual threads for improved concurrency in search operations.
   *
   * @param nexusSearchUrl the web target for search requests
   * @param repository the repository name to search in
   * @param tag the tag to search for
   * @return a list of component maps matching the tag
   */
  @SuppressWarnings("unchecked")
  default List<Map<String, Object>> searchByTag(
      final WebTarget nexusSearchUrl,
      final String repository,
      final String tag)
  {
    waitForSearch();

    return executeWithVirtualThreads(() -> {
      Response response = nexusSearchUrl
          .queryParam("repository", repository)
          .queryParam("tag", tag)
          .request()
          .buildGet()
          .invoke();

      Map<String, Object> map = response.readEntity(Map.class);
      return (List<Map<String, Object>>) map.get("items");
    });
  }
}
