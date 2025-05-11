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

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import java.util.Optional;

/**
 * Group specific handler of Maven indexes: it serves up merged group index, if present.
 * 
 * This handler is optimized for Java 21, leveraging pattern matching for Optional types
 * to improve code readability and maintainability.
 *
 * @since 3.26
 */
class MavenContentIndexGroupHandler
    extends ComponentSupport
    implements Handler
{
  /**
   * Handles requests for Maven index files in group repositories.
   * 
   * This implementation uses Java 21 pattern matching for Optional to provide
   * a more concise and readable approach to handling the presence or absence of content.
   * The handler is designed to be efficient for I/O operations when running under Java 21's
   * virtual thread infrastructure.
   *
   * @param context The request context containing repository and path information
   * @return Response with the index content or a 404 if not found
   * @throws Exception if an error occurs during processing
   */
  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    MavenPath mavenPath = context.getAttributes().require(MavenPath.class);
    MavenContentFacet mavenContentFacet = context.getRepository().facet(MavenContentFacet.class);
    Optional<?> content = mavenContentFacet.get(mavenPath);
    
    // Using Java 21 pattern matching for Optional
    return switch (content) {
      case Optional.of(var asset) -> HttpResponses.ok(asset);
      case Optional.empty() -> HttpResponses.notFound(mavenPath.getPath());
    };
  }
}