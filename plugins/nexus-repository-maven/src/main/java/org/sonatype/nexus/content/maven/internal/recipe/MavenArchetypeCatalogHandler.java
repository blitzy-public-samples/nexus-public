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

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.content.maven.MavenArchetypeCatalogFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;

/**
 * Fetches or rebuilds the maven archetype catalog for a given repository.
 * 
 * Updated for Java 21 to use pattern matching for switch expressions.
 *
 * @since 3.25
 */
@Named
@Singleton
public class MavenArchetypeCatalogHandler
    extends ComponentSupport
    implements Handler
{
  /**
   * Handles the request using Java 21 pattern matching for switch expressions.
   * This implementation uses the enhanced switch syntax with arrow notation
   * which eliminates the need for break statements and provides more concise code.
   */
  @Nonnull
  @Override
  public Response handle(
      @Nonnull final Context context) throws Exception
  {
    String method = context.getRequest().getAction();
    return switch (method) {
      case GET, HEAD -> fetchOrGenerateArchetypeCatalog(context);
      default -> HttpResponses.methodNotAllowed(context.getRequest().getAction(), GET, HEAD);
    };
  }

  /**
   * Attempts to fetch the archetype catalog, and generates it if not found.
   * Uses a more concise conditional expression style aligned with Java 21 practices.
   */
  private Response fetchOrGenerateArchetypeCatalog(final Context context) throws Exception {
    Response response = context.proceed();
    return response.getStatus().isSuccessful() 
        ? response 
        : generateArchetypeCatalog(context);
  }

  /**
   * Generates the archetype catalog for the repository.
   * This method uses the repository's MavenArchetypeCatalogFacet to rebuild the catalog.
   */
  private Response generateArchetypeCatalog(final Context context) throws Exception {
    Repository repository = context.getRepository();
    MavenArchetypeCatalogFacet archetypeCatalogFacet = repository.facet(MavenArchetypeCatalogFacet.class);
    archetypeCatalogFacet.rebuildArchetypeCatalog();
    return context.proceed();
  }
}