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
package org.sonatype.nexus.coreui.internal;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.RepositoryCacheInvalidationService;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.upload.UploadDefinition;
import org.sonatype.nexus.repository.upload.UploadManager;
import org.sonatype.nexus.repository.upload.UploadResponse;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Service for handling repository content uploads.
 * 
 * @since 3.7
 */
@Named
@Singleton
public class UploadService
  extends ComponentSupport
{
  private final UploadManager uploadManager;

  private final RepositoryManager repositoryManager;

  private final RepositoryCacheInvalidationService repositoryCacheInvalidationService;

  private static final String NPM_FORMAT = "npm";

  @Inject
  public UploadService(final RepositoryManager repositoryManager,
                       final UploadManager uploadManager,
                       final RepositoryCacheInvalidationService repositoryCacheInvalidationService)
  {
    this.uploadManager = checkNotNull(uploadManager);
    this.repositoryManager = checkNotNull(repositoryManager);
    this.repositoryCacheInvalidationService = checkNotNull(repositoryCacheInvalidationService);
  }

  /**
   * Get a list of available definitions for upload.
   *
   * @return collection of available upload definitions
   */
  public Collection<UploadDefinition> getAvailableDefinitions() {
    return uploadManager.getAvailableDefinitions();
  }

  /**
   * Perform an upload of assets using Virtual Threads for improved I/O handling.
   *
   * @since 3.16
   *
   * @param repositoryName the repository to upload to
   * @param request a multipart form request
   * @return the query term for results in search (depending on upload could show additional results)
   * @throws IOException if an error occurs during upload processing
   */
  public String upload(final String repositoryName, final HttpServletRequest request) throws IOException {
    checkNotNull(repositoryName, "Repository name cannot be null");
    checkNotNull(request, "HTTP request cannot be null");

    log.debug(STR."Processing upload request for repository: \{repositoryName}");
    
    Repository repository = checkNotNull(repositoryManager.get(repositoryName), 
        STR."Specified repository '\{repositoryName}' is missing");

    // Handle the upload using a virtual thread for better I/O performance
    UploadResponse uploadResponse = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        log.debug(STR."Handling upload for repository \{repository.getName()} with format \{repository.getFormat().getValue()}");
        return uploadManager.handle(repository, request);
      } catch (IOException e) {
        log.error(STR."Error processing upload for repository \{repository.getName()}", e);
        throw new RuntimeException(e);
      }
    }).join();

    // Use pattern matching for format-specific processing
    String format = repository.getFormat().getValue();
    switch (format) {
      case NPM_FORMAT -> {
        log.debug(STR."Invalidating npm caches for repository groups containing \{repositoryName}");
        repositoryManager.findContainingGroups(repositoryName)
            .forEach(groupRepoName -> {
              Repository groupRepo = repositoryManager.get(groupRepoName);
              if (groupRepo != null) {
                repositoryCacheInvalidationService.processCachesInvalidation(groupRepo);
              }
            });
      }
      default -> log.debug(STR."No cache invalidation needed for format: \{format}");
    }

    return createSearchTerm(uploadResponse.getAssetPaths());
  }

  /**
   * Creates a search term based on the common prefix of all created asset paths.
   *
   * @param createdPaths collection of asset paths created during upload
   * @return the common prefix to use as a search term, or null if no paths
   */
  @VisibleForTesting
  String createSearchTerm(final Collection<String> createdPaths) {
    if (createdPaths.isEmpty()) {
      return null;
    }

    String prefix = Iterables.getFirst(createdPaths, null);

    for (String path : createdPaths) {
      prefix = longestPrefix(prefix, path);
    }

    log.debug(STR."Created search term: '\{prefix}' from \{createdPaths.size()} paths");
    return prefix;
  }

  /**
   * Removes the last segment from a path.
   *
   * @param path the path to process
   * @return the path with the last segment removed
   */
  private String removeLastSegment(final String path) {
    int index = path.lastIndexOf('/');
    if (index != -1) {
      return path.substring(0, index);
    }
    return path;
  }

  /**
   * Finds the longest common prefix between the given prefix and path.
   *
   * @param prefix the current prefix
   * @param path the path to compare against
   * @return the longest common prefix
   */
  private String longestPrefix(final String prefix, final String path) {
    String result = prefix;
    while (result.length() > 0 && !path.startsWith(result)) {
      result = removeLastSegment(result);
    }
    return result;
  }
}