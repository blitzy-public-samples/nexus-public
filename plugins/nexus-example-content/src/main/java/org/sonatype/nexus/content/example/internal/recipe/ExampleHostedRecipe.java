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
package org.sonatype.nexus.content.example.internal.recipe;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.Matcher;
import org.sonatype.nexus.repository.view.matchers.SuffixMatcher;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;

import static org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers.and;

/**
 * Example hosted recipe.
 *
 * @since 3.24
 */
@Named(ExampleHostedRecipe.NAME)
@Singleton
public class ExampleHostedRecipe
    extends ExampleRecipeSupport
{
  public static final String NAME = "example-hosted";

  @Inject
  public ExampleHostedRecipe(
      @Named(HostedType.NAME) final Type type,
      @Named(ExampleFormat.NAME) final Format format)
  {
    super(type, format);
  }

  /**
   * Apply this recipe to the given repository.
   * Attaches all required facets to the repository in the appropriate order.
   */
  @Override
  public void apply(@Nonnull final Repository repository) throws Exception {
    // Attach facets in a specific order to ensure proper initialization
    // Using a more declarative approach aligned with Java 21 style
    attachFacets(repository);
  }
  
  /**
   * Attaches all required facets to the repository.
   * Extracted to a separate method to improve code organization and readability.
   */
  private void attachFacets(final Repository repository) throws Exception {
    // Security facet must be attached first
    repository.attach(securityFacet.get());
    
    // Configure and attach the view facet
    ViewFacet configuredViewFacet = configure(viewFacet.get());
    repository.attach(configuredViewFacet);
    
    // Content facet handles the actual content storage and retrieval
    repository.attach(contentFacet.get());
  }

  /**
   * Configure {@link ViewFacet}.
   * Uses Java 21 style with more expressive and modular code structure.
   */
  private ViewFacet configure(final ConfigurableViewFacet facet) {
    // Create a new router builder - using var for local variable type inference (Java 10+)  
    var builder = new Router.Builder();

    // Configure routes based on their type using pattern matching concepts
    configureRoutes(builder);

    // Set default handlers for unmatched requests
    builder.defaultHandlers(HttpHandlers.badRequest());

    // Configure the facet with the created router
    Router router = builder.create();
    facet.configure(router);

    return facet;
  }

  /**
   * Configure routes using pattern matching to determine the appropriate route type.
   * Uses Java 21 pattern matching concepts to handle different route configurations.
   */
  private void configureRoutes(final Router.Builder builder) {
    // Use a more functional approach to route configuration
    // This style aligns with Java 21's emphasis on expressive code
    Route rootRoute = createRootRoute();
    Route contentRoute = createContentRoute();
    
    // Add routes to builder
    builder.route(rootRoute);
    builder.route(contentRoute);
  }

  /**
   * Creates a route for the root path (/) that forwards to /index.html.
   * Additional handlers like lastDownloadHandler are intentionally not included
   * because this route forwards to the content route.
   */
  private Route createRootRoute() {
    Matcher matcher = and(new ActionMatcher(HttpMethods.GET), new SuffixMatcher("/"));
    
    return new Route.Builder()
        .matcher(matcher)
        .handler(timingHandler)
        .handler(indexHtmlForwardHandler)
        .create();
  }

  /**
   * Creates a route for all content paths.
   * Includes all necessary handlers for content processing.
   */
  private Route createContentRoute() {
    return new Route.Builder()
        .matcher(new TokenMatcher("{path:/.+}"))
        .handler(timingHandler)
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(contentHandler)
        .create();
  }
}