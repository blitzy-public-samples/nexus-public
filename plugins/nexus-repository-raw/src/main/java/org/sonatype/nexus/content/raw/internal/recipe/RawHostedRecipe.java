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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.SuffixMatcher;
import org.sonatype.nexus.thread.VirtualThreadFactory;
import org.sonatype.nexus.thread.io.StreamCopier;

import static org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers.and;

/**
 * Raw hosted repository recipe.
 * 
 * Updated for Java 21 to leverage virtual threads for I/O-bound operations.
 *
 * @since 3.24
 */
@AvailabilityVersion(from = "1.0")
@Named(RawHostedRecipe.NAME)
@Singleton
public class RawHostedRecipe
    extends RawRecipeSupport
{
  private static final Logger log = LoggerFactory.getLogger(RawHostedRecipe.class);
  
  public static final String NAME = "raw-hosted";
  
  /**
   * System property to control whether virtual threads should be used for content operations.
   */
  private static final String USE_VIRTUAL_THREADS_PROPERTY = "nexus.raw.useVirtualThreads";
  
  /**
   * Default setting for virtual threads usage (enabled by default).
   */
  private static final boolean USE_VIRTUAL_THREADS_DEFAULT = true;
  
  /**
   * Executor service for handling I/O operations with virtual threads when enabled.
   * This provides significantly improved throughput for concurrent operations.
   */
  private final ExecutorService ioExecutor;

  /**
   * Flag indicating whether virtual threads should be used for content operations.
   */
  private final boolean useVirtualThreads;

  @Inject
  public RawHostedRecipe(@Named(HostedType.NAME) final Type type, @Named(RawFormat.NAME) final Format format) {
    super(type, format);
    this.useVirtualThreads = Boolean.getBoolean(USE_VIRTUAL_THREADS_PROPERTY) || USE_VIRTUAL_THREADS_DEFAULT;
    
    // Create an appropriate executor service based on configuration
    if (useVirtualThreads) {
      // Use virtual threads for I/O operations
      this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
      log.debug("Created virtual thread executor for raw hosted repositories");
    } else {
      // Fall back to null - we won't be using this executor
      this.ioExecutor = null;
    }
  }
  
  @Override
  public void apply(@Nonnull final Repository repository) throws Exception {
    // Create a repository configuration record to track settings
    var config = new RepositoryConfig(repository.getName(), useVirtualThreads);
    
    // Attach all required facets to the repository
    repository.attach(securityFacet.get());
    repository.attach(configure(viewFacet.get()));
    repository.attach(contentFacet.get());
    repository.attach(maintenanceFacet.get());
    repository.attach(searchFacet.get());
    repository.attach(browseFacet.get());
    
    // Log whether virtual threads are enabled for this repository using string template
    if (config.useVirtualThreads()) {
      log.info(STR."Raw hosted repository \{config.name()} configured to use Java 21 virtual threads for I/O operations");
    }
  }

  /**
   * Configure {@link ViewFacet} with optimized handlers for Java 21.
   * <p>
   * This implementation leverages virtual threads for I/O-bound operations when enabled,
   * providing significantly improved throughput for concurrent operations without
   * the overhead of traditional platform threads.
   */
  private ViewFacet configure(final ConfigurableViewFacet facet) {
    Router.Builder builder = new Router.Builder();

    // Configure StreamCopier to use virtual threads if enabled
    if (useVirtualThreads) {
      StreamCopier.configureVirtualThreads(true);
    }

    // Additional handlers, such as the lastDownloadHandler, are intentionally
    // not included on this route because this route forwards to the route below.
    // This route specifically handles GET / and forwards to /index.html.
    builder.route(new Route.Builder()
        .matcher(and(new ActionMatcher(HttpMethods.GET), new SuffixMatcher("/")))
        .handler(timingHandler)
        .handler(indexHtmlForwardHandler)
        .create());

    // Configure the main content route with appropriate handlers
    // When using virtual threads, I/O operations in contentHandler will be executed
    // on lightweight virtual threads for improved scalability
    var contentRoute = new Route.Builder()
        .matcher(PATH_MATCHER)
        .handler(timingHandler)
        .handler(contentDispositionHandler)
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor);
        
    // Apply conditional request handling
    contentRoute.handler(conditionalRequestHandler);
    
    // Apply partial fetch handling
    contentRoute.handler(partialFetchHandler);
    
    // Apply content headers handling
    contentRoute.handler(contentHeadersHandler);
    
    // Track last downloaded time
    contentRoute.handler(lastDownloadedHandler);
    
    // Apply the main content handler - this is where virtual threads will be used for I/O operations
    contentRoute.handler(contentHandler);
    
    // Add the route to the builder
    builder.route(contentRoute.create());

    // Configure default handlers for unmatched requests
    builder.defaultHandlers(HttpHandlers.badRequest());

    // Apply the router configuration to the facet
    facet.configure(builder.create());

    return facet;
  }
  
  /**
   * Record for repository configuration.
   * Using Java 21 record pattern for immutable data objects.
   */
  private record RepositoryConfig(String name, boolean useVirtualThreads) {
    /**
     * Creates a new repository configuration with the given settings.
     *
     * @param name the repository name
     * @param useVirtualThreads whether virtual threads are enabled for this repository
     */
    public RepositoryConfig {
      // Validate inputs
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Repository name cannot be null or blank");
      }
    }
    
    /**
     * Returns a string representation of this configuration.
     *
     * @return a string representation
     */
    @Override
    public String toString() {
      return STR."RepositoryConfig[name=\{name}, useVirtualThreads=\{useVirtualThreads}]";
    }
  }
}