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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.httpbridge.LegacyViewConfiguration;
import org.sonatype.nexus.repository.httpbridge.LegacyViewContributor;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionHelper;
import org.sonatype.nexus.repository.httpbridge.internal.describe.DescriptionRenderer;
import org.sonatype.nexus.repository.manager.RepositoryManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.http.HttpResponses.notFound;

/**
 * Repository view servlet for NX2 urls.
 *
 * @since 3.7
 */
@Named
@Singleton
public class LegacyViewServlet
    extends ViewServlet
{
  private static final Logger log = LoggerFactory.getLogger(LegacyViewServlet.class);
  
  private final List<LegacyViewContributor> legacyViewContributors;

  private final RepositoryManager repositoryManager;

  /**
   * Record to hold the result of format matching operations
   */
  private record FormatMatchResult(boolean matched, Repository repository, LegacyViewConfiguration configuration) {}

  @Inject
  public LegacyViewServlet(final RepositoryManager repositoryManager,
                           final HttpResponseSenderSelector httpResponseSenderSelector,
                           final DescriptionHelper descriptionHelper,
                           final DescriptionRenderer descriptionRenderer,
                           final List<LegacyViewContributor> legacyViewContributors,
                           @Named("${nexus.repository.sandbox.enable:-true}") final boolean sandboxEnabled)
  {
    super(repositoryManager, httpResponseSenderSelector, descriptionHelper, descriptionRenderer, sandboxEnabled);
    this.legacyViewContributors = checkNotNull(legacyViewContributors);
    this.repositoryManager = repositoryManager;
  }

  @Override
  protected void doService(final HttpServletRequest request, final HttpServletResponse response)
      throws Exception
  {
    if (handleFormatSpecificUri(request, response)) {
      return;
    }

    super.doService(request, response);
  }

  private boolean handleFormatSpecificUri(final HttpServletRequest request, final HttpServletResponse response)
      throws ServletException, IOException
  {
    try {
      // Use a virtual thread to process format-specific URI handling which may involve I/O
      return Thread.startVirtualThread(() -> {
        try {
          return processFormatSpecificUri(request, response);
        }
        catch (Exception e) {
          log.error(STR."Error processing format-specific URI: {e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServletException("Format-specific URI processing interrupted", e);
    }
    catch (Exception e) {
      if (e.getCause() instanceof ServletException servletException) {
        throw servletException;
      }
      if (e.getCause() instanceof IOException ioException) {
        throw ioException;
      }
      throw new ServletException("Error processing format-specific URI", e);
    }
  }

  private boolean processFormatSpecificUri(final HttpServletRequest request, final HttpServletResponse response)
      throws ServletException, IOException
  {
    for (LegacyViewContributor legacyViewContributor : legacyViewContributors) {
      LegacyViewConfiguration configuration = legacyViewContributor.contribute();
      FormatMatchResult result = checkFormatMatch(request, configuration);
      
      // Use pattern matching to check the result
      if (result instanceof FormatMatchResult(true, Repository repository, LegacyViewConfiguration config)) {
        if (repository == null || !Objects.equals(config.getFormat(), repository.getFormat().getValue())) {
          send(null, notFound(REPOSITORY_NOT_FOUND_MESSAGE), response);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks if the request matches a specific format configuration
   * 
   * @param request the HTTP servlet request
   * @param configuration the legacy view configuration to check against
   * @return a FormatMatchResult containing the match result and related information
   */
  private FormatMatchResult checkFormatMatch(final HttpServletRequest request, final LegacyViewConfiguration configuration) {
    Matcher matcher = configuration.getRequestPattern().matcher(request.getRequestURI());
    if (matcher.matches()) {
      RepositoryPath path = RepositoryPath.parse(request.getPathInfo());
      Repository repository = repositoryManager.get(path.repositoryName());
      return new FormatMatchResult(true, repository, configuration);
    }
    return new FormatMatchResult(false, null, configuration);
  }
}
