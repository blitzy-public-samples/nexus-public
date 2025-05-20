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
package org.sonatype.nexus.repository.group;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Response;

/**
 * Merges responses from member repositories using Virtual Threads for parallel processing.
 */
public abstract class SingleMetadataMergingGroupHandlerSupport
    extends MergingGroupHandlerSupport
{
  @Override
  protected Optional<Content> merge(
      final Context context,
      final Map<Repository, Response> successfulResponses,
      final Optional<String> optEtag) throws IOException
  {
    Optional<Content> result = mergeWithVirtualThreads(successfulResponses.values()).map(Content::new);

    if (result.isPresent() && optEtag.isPresent()) {
      result.map(Content::getAttributes)
          .ifPresent(attributes -> attributes.set(Content.CONTENT_ETAG, optEtag.get()));

      result = Optional.of(store(context, result.get()));
    }
    else {
      log.debug("Unable to compute lastModified or value");
    }

    return result;
  }

  /**
   * Consume & merge the responses provided to generate the merged metadata using Virtual Threads for parallel processing.
   *
   * @param successfulResponses a collection of successful responses to be merged
   * @return a constructed payload, implementors should not persist the value
   */
  protected Optional<Payload> mergeWithVirtualThreads(Collection<Response> successfulResponses) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process responses in parallel using Virtual Threads
      var futures = successfulResponses.stream()
          .map(response -> executor.submit(() -> processResponse(response)))
          .collect(Collectors.toList());
      
      // Collect and combine results
      return combineResults(futures);
    } catch (Exception e) {
      log.error("Error processing responses with Virtual Threads", e);
      return Optional.empty();
    }
  }

  /**
   * Process an individual response. This method will be executed in a Virtual Thread.
   * Subclasses can override this method to provide custom processing logic.
   *
   * @param response the response to process
   * @return the processed response data
   */
  protected Object processResponse(Response response) {
    // Default implementation just returns the response
    // Subclasses should override this method to provide custom processing logic
    return response;
  }

  /**
   * Combine the results from parallel processing into a single payload.
   *
   * @param futures the futures containing processed response data
   * @return the combined payload
   */
  protected Optional<Payload> combineResults(Collection<Future<?>> futures) {
    try {
      // Collect all results
      var results = futures.stream()
          .map(future -> {
            try {
              return future.get();
            } catch (InterruptedException | ExecutionException e) {
              log.debug("Error getting result from future", e);
              Thread.currentThread().interrupt();
              return null;
            }
          })
          .filter(result -> result != null)
          .collect(Collectors.toList());
      
      // Delegate to the original merge method for actual merging logic
      return merge(results.stream()
          .map(result -> (Response) result)
          .collect(Collectors.toList()));
    } catch (Exception e) {
      log.error("Error combining results", e);
      return Optional.empty();
    }
  }

  /**
   * Consume & merge the responses provided to generate the merged metadata.
   * This method is called by combineResults after parallel processing.
   *
   * @param successfulResponses a list of successful responses to be merged
   * @return a constructed payload, implementors should not persist the value
   */
  protected abstract Optional<Payload> merge(Collection<Response> successfulResponses);

  /**
   * Persist the content merged for the context
   *
   * @param context the request context
   * @param content the content to persist
   * @return the persisted content
   * @throws IOException
   */
  protected abstract Content store(Context context, Content content) throws IOException;
}