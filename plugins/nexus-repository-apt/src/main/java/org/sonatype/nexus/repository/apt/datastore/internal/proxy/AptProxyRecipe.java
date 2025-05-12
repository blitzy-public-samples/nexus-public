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
package org.sonatype.nexus.repository.apt.datastore.internal.proxy;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.apt.datastore.AptContentFacet;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.apt.internal.AptSecurityFacet;
import org.sonatype.nexus.repository.apt.internal.snapshot.AptSnapshotHandler;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;
import org.sonatype.nexus.repository.view.matchers.AlwaysMatcher;

import static org.sonatype.nexus.repository.http.HttpHandlers.notFound;

/**
 * Apt proxy repository recipe.
 * 
 * This recipe configures an APT proxy repository with all necessary facets and handlers.
 * It leverages Java 21 features including Virtual Threads for improved I/O performance
 * in proxy operations, particularly when fetching remote content.
 *
 * @since 3.31
 */
@AvailabilityVersion(from = "1.0")
@Named(AptProxyRecipe.NAME)
@Singleton
public class AptProxyRecipe
    extends RecipeSupport
{
  public static final String NAME = "apt-proxy";

  @Inject
  Provider<AptSecurityFacet> securityFacet;

  @Inject
  Provider<ConfigurableViewFacet> viewFacet;

  @Inject
  Provider<HttpClientFacet> httpClientFacet;

  @Inject
  Provider<NegativeCacheFacet> negativeCacheFacet;

  @Inject
  Provider<AptProxyFacet> proxyFacet;

  @Inject
  Provider<AptProxySnapshotFacet> proxySnapshotFacet;

  @Inject
  Provider<PurgeUnusedFacet> purgeUnusedFacet;

  @Inject
  Provider<LastAssetMaintenanceFacet> lastAssetMaintenanceFacet;

  @Inject
  Provider<AptContentFacet> aptContentFacet;

  @Inject
  Provider<SearchFacet> searchFacet;

  @Inject
  Provider<BrowseFacet> browseFacet;

  @Inject
  ExceptionHandler exceptionHandler;

  @Inject
  TimingHandler timingHandler;

  @Inject
  SecurityHandler securityHandler;

  @Inject
  NegativeCacheHandler negativeCacheHandler;

  @Inject
  PartialFetchHandler partialFetchHandler;

  @Inject
  ProxyHandler proxyHandler;

  @Inject
  ConditionalRequestHandler conditionalRequestHandler;

  @Inject
  ContentHeadersHandler contentHeadersHandler;

  @Inject
  AptSnapshotHandler snapshotHandler;

  @Inject
  LastDownloadedHandler lastDownloadedHandler;

  @Inject
  RoutingRuleHandler routingRuleHandler;

  @Inject
  HandlerContributor handlerContributor;

  @Inject
  public AptProxyRecipe(@Named(ProxyType.NAME) final Type type, @Named(AptFormat.NAME) final Format format)
  {
    super(type, format);
  }

  /**
   * Applies this recipe to the given repository by attaching all required facets.
   * 
   * This method configures the repository with facets that leverage Java 21 features,
   * particularly {@link AptProxyFacet} and {@link AptProxySnapshotFacet} which use
   * Virtual Threads for improved I/O performance when fetching remote content.
   *
   * @param repository the repository to apply this recipe to
   * @throws Exception if an error occurs during application of the recipe
   */
  @Override
  public void apply(final Repository repository) throws Exception {
    repository.attach(securityFacet.get());
    repository.attach(configure(viewFacet.get()));
    repository.attach(httpClientFacet.get());
    repository.attach(negativeCacheFacet.get());
    repository.attach(proxyFacet.get());
    repository.attach(proxySnapshotFacet.get());
    repository.attach(aptContentFacet.get());
    repository.attach(browseFacet.get());
    repository.attach(lastAssetMaintenanceFacet.get());
    repository.attach(purgeUnusedFacet.get());
    repository.attach(searchFacet.get());
  }

  /**
   * Configures the view facet with a router that handles all requests through a chain of handlers.
   * 
   * In Java 21, these handlers benefit from Virtual Threads for improved I/O performance,
   * particularly the {@link ProxyHandler} which handles remote content fetching operations.
   * The handlers are executed in a thread-per-request model using Virtual Threads, allowing
   * for more efficient handling of concurrent requests without consuming excessive resources.
   *
   * @param facet the configurable view facet to configure
   * @return the configured view facet
   */
  private ViewFacet configure(final ConfigurableViewFacet facet) {
    Router.Builder builder = new Router.Builder();

    builder.route(new Route.Builder().matcher(new AlwaysMatcher())
        .handler(timingHandler)
        .handler(securityHandler)
        .handler(routingRuleHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(snapshotHandler)
        .handler(lastDownloadedHandler)
        .handler(proxyHandler).create());

    builder.defaultHandlers(notFound());
    facet.configure(builder.create());
    return facet;
  }
}