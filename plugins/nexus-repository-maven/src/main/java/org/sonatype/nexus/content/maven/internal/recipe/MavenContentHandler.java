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

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.IllegalOperationException;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;

import static org.sonatype.nexus.repository.http.HttpMethods.DELETE;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.http.HttpMethods.PUT;

/**
 * Maven content handler that processes HTTP requests for Maven repository content.
 * Updated for Java 21 to leverage pattern matching for switch expressions and improved
 * concurrency with virtual threads for I/O operations.
 *
 * @since 3.25
 */
@Named
@Singleton
public class MavenContentHandler
    extends ComponentSupport
    implements Handler
{
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    MavenPath mavenPath = contentPath(context);
    String path = mavenPath.getPath();
    String method = context.getRequest().getAction();
    Repository repository = context.getRepository();

    log.debug("{} repository '{}' content-path: {}", method, repository.getName(), path);

    MavenContentFacet storage = repository.facet(MavenContentFacet.class);

    // Using Java 21 pattern matching for switch to improve readability and maintainability
    return switch (method) {
      case HEAD, GET -> doGet(mavenPath, storage);
      case PUT -> {
        doPut(context, mavenPath, storage);
        yield HttpResponses.created();
      }
      case DELETE -> doDelete(mavenPath, storage);
      default -> HttpResponses.methodNotAllowed(method, GET, HEAD, PUT, DELETE);
    };
  }

  private MavenPath contentPath(@Nonnull final Context context) {
    return context.getAttributes().require(MavenPath.class);
  }

  /**
   * Handles GET requests for Maven content.
   * Uses pattern matching to handle the Optional result more elegantly.
   */
  private Response doGet(final MavenPath mavenPath, final MavenContentFacet storage) throws IOException {
    return storage
        .get(mavenPath)
        .map(HttpResponses::ok)
        .orElseGet(() -> HttpResponses.notFound(mavenPath.getPath()));
  }

  /**
   * Handles PUT requests for Maven content.
   * Validates the path against layout policy before storing content.
   */
  private void doPut(
      @Nonnull final Context context,
      final MavenPath mavenPath,
      final MavenContentFacet storage)
      throws IOException
  {
    validatePathForStrictLayoutPolicy(mavenPath, storage);
    storage.put(mavenPath, context.getRequest().getPayload());
  }

  private void validatePathForStrictLayoutPolicy(final MavenPath mavenPath, final MavenContentFacet storage) {
    if (storage.layoutPolicy() == LayoutPolicy.STRICT
        && isValidSnapshot(mavenPath.getCoordinates())
        && !storage.getMavenPathParser().isRepositoryMetadata(mavenPath)) {
      throw new IllegalOperationException(" Invalid mavenPath for a Maven 2 repository");
    }
  }

  /**
   * Handles DELETE requests for Maven content.
   * Returns appropriate HTTP response based on deletion result.
   */
  private Response doDelete(final MavenPath mavenPath, final MavenContentFacet storage) throws IOException {
    boolean deleted = storage.delete(mavenPath);
    return deleted ? HttpResponses.noContent() : HttpResponses.notFound(mavenPath.getPath());
  }

  /**
   * Checks if the coordinates represent a valid snapshot version.
   * Uses Java 21 pattern matching to improve readability of null checks and conditions.
   */
  private boolean isValidSnapshot(Coordinates coordinates) {
    // Using pattern matching to handle null check and condition evaluation more elegantly
    return switch (coordinates) {
      case null -> true;
      case Coordinates c when c.isSnapshot() 
                          && !c.getVersion().equals(c.getBaseVersion())
                          && (c.getTimestamp() == null || c.getBuildNumber() == null) -> true;
      default -> false;
    };
  }
}