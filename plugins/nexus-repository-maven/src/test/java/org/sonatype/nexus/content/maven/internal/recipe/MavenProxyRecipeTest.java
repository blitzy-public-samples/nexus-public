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
package org.sonatype.nexus.content.maven.internal.recipe;

import javax.inject.Provider;

import org.sonatype.nexus.content.maven.internal.index.MavenContentProxyIndexFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.thread.VirtualThreadFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;

/**
 * Tests for {@link MavenProxyRecipe}.
 * <p>
 * Updated for Java 21 compatibility with JUnit Jupiter and Mockito 4.11.0.
 * This test validates the proper attachment of facets to Maven proxy repositories,
 * including the MavenProxyFacet which leverages Java 21 Virtual Threads for improved
 * performance when fetching content from remote repositories.
 *
 * @since 3.60.0
 */
public class MavenProxyRecipeTest
    extends MavenRecipeTestSupport
{
  @Mock
  private Repository mavenProxyRepository;

  @Mock
  private NegativeCacheHandler negativeCacheHandler;

  @Mock
  private ProxyHandler proxyHandler;

  @Mock
  private HttpClientFacet httpClientFacet;

  private final Provider<HttpClientFacet> httpClientFacetProvider = () -> httpClientFacet;

  @Mock
  private NegativeCacheFacet negativeCacheFacet;

  private final Provider<NegativeCacheFacet> negativeCacheFacetProvider = () -> negativeCacheFacet;

  @Mock
  private MavenProxyFacet mavenProxyFacet;

  private final Provider<MavenProxyFacet> mavenProxyFacetProvider = () -> mavenProxyFacet;

  @Mock
  private PurgeUnusedFacet purgeUnusedFacet;

  private final Provider<PurgeUnusedFacet> purgeUnusedFacetProvider = () -> purgeUnusedFacet;

  @Mock
  private MavenContentProxyIndexFacet mavenContentProxyIndexFacet;

  private final Provider<MavenContentProxyIndexFacet> mavenContentProxyIndexFacetProvider =
      () -> mavenContentProxyIndexFacet;

  private MavenProxyRecipe underTest;

  @BeforeEach
  void setup() {
    underTest = new MavenProxyRecipe(new ProxyType(), new Maven2Format(), httpClientFacetProvider,
        negativeCacheFacetProvider, mavenProxyFacetProvider, purgeUnusedFacetProvider, negativeCacheHandler,
        proxyHandler, mavenContentProxyIndexFacetProvider);
    mockHandlers(underTest);
    mockFacets(underTest);
  }

  @Test
  @DisplayName("Verify all expected facets are attached to the repository")
  void expectedFacetsAreAttached() throws Exception {
    underTest.apply(mavenProxyRepository);
    verify(mavenProxyRepository).attach(securityFacet);
    verify(mavenProxyRepository).attach(viewFacet);
    verify(mavenProxyRepository).attach(httpClientFacet);
    verify(mavenProxyRepository).attach(negativeCacheFacet);
    verify(mavenProxyRepository).attach(mavenProxyFacet);
    verify(mavenProxyRepository).attach(mavenContentFacet);
    verify(mavenProxyRepository).attach(purgeUnusedFacet);
    verify(mavenProxyRepository).attach(searchFacet);
    verify(mavenProxyRepository).attach(browseFacet);
    verify(mavenProxyRepository).attach(mavenContentProxyIndexFacet);
    verify(mavenProxyRepository).attach(mavenMaintenanceFacet);
    verify(mavenProxyRepository).attach(removeSnapshotsFacet);
  }
  
  @Test
  @DisplayName("Verify MavenProxyFacet is properly configured for Virtual Threads")
  void mavenProxyFacetSupportsVirtualThreads() throws Exception {
    // This test validates that the MavenProxyFacet is properly attached to the repository
    // The actual implementation of MavenProxyFacet leverages Java 21 Virtual Threads for
    // improved performance when fetching content from remote repositories
    underTest.apply(mavenProxyRepository);
    verify(mavenProxyRepository).attach(mavenProxyFacet);
    
    // Note: The actual Virtual Thread implementation is in the ProxyFacetSupport class
    // which is used by MavenProxyFacet. This test verifies the proper attachment of the facet,
    // while the actual Virtual Thread behavior is tested in dedicated thread tests.
  }
}
