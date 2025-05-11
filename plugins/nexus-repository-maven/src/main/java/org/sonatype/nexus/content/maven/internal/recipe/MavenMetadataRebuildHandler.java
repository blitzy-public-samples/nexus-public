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
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.maven.MavenMetadataRebuildFacet;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import static org.apache.commons.lang3.StringUtils.prependIfMissing;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;

/**
 * Handler for rebuilding Maven metadata when appropriate.
 * <p>
 * Updated for Java 21 to leverage pattern matching for switch expressions and statements.
 * 
 * @since 3.26
 */
@Named
@Singleton
public class MavenMetadataRebuildHandler
    extends ComponentSupport
    implements Handler
{
  private static final String PATH_PREFIX = "/";

  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception
  {
    String method = context.getRequest().getAction();
    Repository repository = context.getRepository();
    
    // Using Java 21 pattern matching for switch to handle HTTP method checking
    switch (method) {
      case GET, HEAD when isNotProxy(repository) -> {
        repository.facet(MavenMetadataRebuildFacet.class)
            .maybeRebuildMavenMetadata(prependIfMissing(context.getRequest().getPath(), PATH_PREFIX), false, true);
      }
      default -> { /* No action needed for other methods */ }
    }
    
    return context.proceed();
  }

  /**
   * Determines if the repository is not a proxy type repository.
   * 
   * @param repository The repository to check
   * @return true if the repository is not a proxy type, false otherwise
   */
  protected boolean isNotProxy(@Nonnull final Repository repository) {
    // Using Java 21 pattern matching for switch to check repository type
    return switch (repository.getType().getValue()) {
      case ProxyType.NAME -> false;
      default -> true;
    };
  }
}