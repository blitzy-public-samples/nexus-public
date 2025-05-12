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
package org.sonatype.nexus.content.raw.internal.recipe;

import javax.inject.Provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawProxyRecipe} to verify proper configuration and attachment of facets.
 * <p>
 * This test has been updated to use JUnit Jupiter (JUnit 5) and is compatible with Java 21.
 */
public class RawProxyRecipeTest
    extends RawRecipeTestSupport
{
  @Mock
  private Repository rawProxyRepository;

  @Mock
  private NegativeCacheHandler negativeCacheHandler;

  @Mock
  private PartialFetchHandler partialFetchHandler;

  @Mock
  private ProxyHandler proxyHandler;

  @Mock
  private ConditionalRequestHandler conditionalRequestHandler;

  @Mock
  private HandlerContributor handlerContributor;

  @Mock
  private RoutingRuleHandler routingRuleHandler;

  @Mock
  private HttpClientFacet httpClientFacet;

  private final Provider<HttpClientFacet> httpClientFacetProvider = () -> httpClientFacet;

  @Mock
  private NegativeCacheFacet negativeCacheFacet;

  private final Provider<NegativeCacheFacet> negativeCacheFacetProvider = () -> negativeCacheFacet;

  @Mock
  private RawProxyFacet proxyFacet;

  private final Provider<RawProxyFacet> rawProxyFacetProvider = () -> proxyFacet;

  @Mock
  private PurgeUnusedFacet purgeUnusedFacet;

  private final Provider<PurgeUnusedFacet> purgeUnusedFacetProvider = () -> purgeUnusedFacet;

  private RawProxyRecipe underTest;

  /**
   * Sets up the test environment before each test execution.
   * <p>
   * Creates a new instance of {@link RawProxyRecipe} with all required dependencies
   * and configures mock behavior.
   */
  @BeforeEach
  void setup() {
    underTest =
        new RawProxyRecipe(new ProxyType(), new RawFormat(), httpClientFacetProvider, negativeCacheFacetProvider,
            rawProxyFacetProvider, purgeUnusedFacetProvider, negativeCacheHandler, proxyHandler, routingRuleHandler);
    mockDependencies(underTest);
  }

  /**
   * Verifies that all expected facets are properly attached to the repository when the recipe is applied.
   * <p>
   * This test ensures that the recipe correctly configures a raw proxy repository with all required facets.
   */
  @Test
  @DisplayName("Should attach all required facets when recipe is applied")
  void shouldAttachAllRequiredFacets() throws Exception {
    underTest.apply(rawProxyRepository);
    verify(rawProxyRepository).attach(securityFacet);
    verify(rawProxyRepository).attach(viewFacet);
    verify(rawProxyRepository).attach(httpClientFacet);
    verify(rawProxyRepository).attach(negativeCacheFacet);
    verify(rawProxyRepository).attach(proxyFacet);
    verify(rawProxyRepository).attach(contentFacet);
    verify(rawProxyRepository).attach(maintenanceFacet);
    verify(rawProxyRepository).attach(searchFacet);
    verify(rawProxyRepository).attach(browseFacet);
    verify(rawProxyRepository).attach(purgeUnusedFacet);
  }
  
  /**
   * Tests concurrent repository operations using Java 21 Virtual Threads.
   * <p>
   * This test demonstrates the use of Java 21 features:
   * - Virtual Threads for lightweight concurrency
   * - Pattern matching for instanceof
   * - Enhanced exception handling
   * <p>
   * The test simulates multiple concurrent repository operations and verifies
   * that all operations complete successfully.
   */
  @Test
  @EnabledOnJre(JRE.JAVA_21)
  @DisplayName("Should handle concurrent operations using virtual threads")
  void shouldHandleConcurrentOperationsUsingVirtualThreads() throws Exception {
    // Number of concurrent operations to simulate
    final int concurrentOperations = 10;
    final CountDownLatch latch = new CountDownLatch(concurrentOperations);
    final List<Exception> exceptions = new CopyOnWriteArrayList<>();
    
    // Configure mock behavior
    when(rawProxyRepository.getName()).thenReturn("raw-proxy-test");
    
    // Create a record to hold operation results
    record OperationResult(String repositoryName, boolean success, Duration duration) {}
    List<OperationResult> results = new CopyOnWriteArrayList<>();
    
    // Use virtual threads executor (Java 21 feature)
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple concurrent operations
      for (int i = 0; i < concurrentOperations; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            long startTime = System.nanoTime();
            
            // Apply the recipe to the repository
            underTest.apply(rawProxyRepository);
            
            // Calculate operation duration
            Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
            
            // Store the result using a record (Java 21 feature)
            results.add(new OperationResult(rawProxyRepository.getName() + "-" + operationId, true, duration));
          }
          catch (Exception e) {
            // Use pattern matching for instanceof (Java 21 feature)
            if (e instanceof RuntimeException rte && rte.getMessage() != null) {
              exceptions.add(new RuntimeException("Operation " + operationId + " failed: " + rte.getMessage()));
            } else {
              exceptions.add(e);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
    }
    
    // Verify results
    assertEquals(0, exceptions.size(), "Some operations failed: " + exceptions);
    assertEquals(concurrentOperations, results.size(), "Not all operations completed successfully");
    
    // Verify that all required facets were attached for each operation
    verify(rawProxyRepository, Mockito.times(concurrentOperations)).attach(securityFacet);
    verify(rawProxyRepository, Mockito.times(concurrentOperations)).attach(viewFacet);
    verify(rawProxyRepository, Mockito.times(concurrentOperations)).attach(httpClientFacet);
    
    // Use pattern matching with records to process results (Java 21 feature)
    for (OperationResult result : results) {
      // Destructure the record using pattern matching
      var OperationResult(name, success, duration) = result;
      assertTrue(success, "Operation " + name + " failed");
      assertTrue(duration.toMillis() >= 0, "Invalid duration for operation " + name);
    }
  }
}
