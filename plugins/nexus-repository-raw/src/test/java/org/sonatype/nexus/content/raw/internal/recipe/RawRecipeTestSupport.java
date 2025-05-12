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

import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.SingleAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.raw.ContentDispositionHandler;
import org.sonatype.nexus.repository.raw.internal.RawIndexHtmlForwardHandler;
import org.sonatype.nexus.repository.raw.internal.RawSecurityFacet;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Test support class for Raw repository recipes, providing mock dependencies for testing.
 * <p>
 * This class is compatible with JUnit Jupiter (JUnit 5) and Mockito 5.x running on Java 21.
 * It uses MockitoExtension for JUnit Jupiter integration instead of extending a base test class.
 */
@ExtendWith(MockitoExtension.class)
public abstract class RawRecipeTestSupport
{
  @Mock
  private ExceptionHandler exceptionHandler;

  @Mock
  private TimingHandler timingHandler;

  @Mock
  private RawIndexHtmlForwardHandler indexHtmlForwardHandler;

  @Mock
  private SecurityHandler securityHandler;

  @Mock
  private PartialFetchHandler partialFetchHandler;

  @Mock
  private RawContentHandler contentHandler;

  @Mock
  private ConditionalRequestHandler conditionalRequestHandler;

  @Mock
  private ContentHeadersHandler contentHeadersHandler;

  @Mock
  private LastDownloadedHandler lastDownloadedHandler;

  @Mock
  private HandlerContributor handlerContributor;

  @Mock
  private ContentDispositionHandler contentDispositionHandler;

  @Mock
  protected RawSecurityFacet securityFacet;

  private final Provider<RawSecurityFacet> securityFacetProvider = () -> securityFacet;

  @Mock
  protected ConfigurableViewFacet viewFacet;

  private final Provider<ConfigurableViewFacet> viewFacetProvider = () -> viewFacet;

  @Mock
  protected RawContentFacet contentFacet;

  private final Provider<RawContentFacet> contentFacetProvider = () -> contentFacet;

  @Mock
  protected SingleAssetMaintenanceFacet maintenanceFacet;

  private final Provider<SingleAssetMaintenanceFacet> maintenanceFacetProvider = () -> maintenanceFacet;

  @Mock
  protected SearchFacet searchFacet;

  private final Provider<SearchFacet> searchFacetProvider = () -> searchFacet;

  @Mock
  protected BrowseFacet browseFacet;

  private final Provider<BrowseFacet> browseFacetProvider = () -> browseFacet;

  /**
   * Sets up mock dependencies for the recipe under test.
   * <p>
   * This method injects all required dependencies into the recipe instance,
   * allowing tests to focus on the specific behavior being tested without
   * needing to manually configure each dependency.
   *
   * @param underTest the recipe instance being tested
   * @param <T> the type of recipe support being tested
   */
  protected <T extends RawRecipeSupport> void mockDependencies(final T underTest) {
    underTest.setDependencies(securityFacetProvider, viewFacetProvider, contentFacetProvider, maintenanceFacetProvider,
        searchFacetProvider, browseFacetProvider, exceptionHandler, timingHandler, indexHtmlForwardHandler,
        securityHandler, partialFetchHandler, contentHandler, conditionalRequestHandler, contentHeadersHandler,
        lastDownloadedHandler, handlerContributor, contentDispositionHandler);
  }
}