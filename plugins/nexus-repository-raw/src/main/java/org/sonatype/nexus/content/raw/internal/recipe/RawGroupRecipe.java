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
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.raw.internal.RawFormat;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Raw group repository recipe.
 * 
 * Updated for Java 21 to leverage Virtual Threads for concurrent operations
 * and modern language features for improved code quality.
 *
 * @since 3.24
 */
@AvailabilityVersion(from = "1.0")
@Named(RawGroupRecipe.NAME)
@Singleton
public class RawGroupRecipe
    extends RawRecipeSupport
{
  public static final String NAME = "raw-group";

  private final Provider<GroupFacet> groupFacet;

  private final GroupHandler groupHandler;

  @Inject
  public RawGroupRecipe(
      @Named(GroupType.NAME) final Type type,
      @Named(RawFormat.NAME) final Format format,
      @Named("default") final Provider<GroupFacet> groupFacet,
      @Named("default") final GroupHandler groupHandler)
  {
    super(type, format);
    this.groupFacet = checkNotNull(groupFacet);
    this.groupHandler = checkNotNull(groupHandler);
  }

  /**
   * Apply the repository configuration using Java 21 Virtual Threads for concurrent operations.
   * This improves performance for I/O-bound operations when attaching facets.
   */
  @Override
  public void apply(@Nonnull final Repository repository) throws Exception {
    // Use Java 21 Virtual Threads for concurrent facet attachment
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit facet attachment tasks to be executed concurrently
      futures.add(executor.submit(() -> repository.attach(securityFacet.get())));
      futures.add(executor.submit(() -> repository.attach(configure(viewFacet.get()))));
      futures.add(executor.submit(() -> repository.attach(groupFacet.get())));
      futures.add(executor.submit(() -> repository.attach(contentFacet.get())));
      futures.add(executor.submit(() -> repository.attach(browseFacet.get())));
      
      // Wait for all facet attachments to complete
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if any of the tasks failed
      }
    }
  }

  /**
   * Configure {@link ViewFacet} using Java 21 pattern matching for improved code readability.
   */
  private ViewFacet configure(final ConfigurableViewFacet viewFacet) {
    // Using pattern matching to ensure viewFacet is of the correct type
    if (viewFacet instanceof ConfigurableViewFacet facet) {
      Router.Builder builder = new Router.Builder();

      builder.route(new Route.Builder()
          .matcher(PATH_MATCHER)
          .handler(timingHandler)
          .handler(contentDispositionHandler)
          .handler(securityHandler)
          .handler(exceptionHandler)
          .handler(handlerContributor)
          .handler(groupHandler)
          .create());

      builder.defaultHandlers(HttpHandlers.badRequest());

      // Using Java 21 string templates for logging (if needed)
      log.debug(STR."Configuring view facet for repository type: \{facet.getClass().getSimpleName()}");
      
      facet.configure(builder.create());
      return facet;
    } else {
      // Using Java 21 string templates for error messages
      throw new IllegalArgumentException(STR."Expected ConfigurableViewFacet but got: \{viewFacet.getClass().getName()}");
    }
  }
}