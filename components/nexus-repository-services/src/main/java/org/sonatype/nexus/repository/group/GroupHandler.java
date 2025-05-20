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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.ViewFacet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import static java.util.Collections.unmodifiableSet;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_NAME;
import static org.sonatype.nexus.repository.proxy.ProxyFacetSupport.BYPASS_HTTP_ERRORS_HEADER_VALUE;

/**
 * Group handler.
 *
 * @since 3.0
 */
@Named("default")
@Singleton
public class GroupHandler
    extends ComponentSupport
    implements Handler
{
  public static final String IGNORE_FIREWALL = "IGNORE_FIREWALL";

  public static final String USE_DISPATCHED_RESPONSE = "USE_DISPATCHED_RESPONSE";

  public static final String INSUFFICIENT_LICENSE =
      "Deploying to groups is a PRO-licensed feature. See https://links.sonatype.com/product-nexus-repository";

  /**
   * Virtual Thread executor for concurrent repository operations.
   * Uses Java 21's Virtual Threads for efficient I/O-bound operations.
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Default timeout for repository operations in seconds.
   */
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;

  /**
   * Request-context state container for set of repositories already dispatched to.
   */
  @VisibleForTesting
  public static class DispatchedRepositories
  {
    private final Set<String> dispatched = Sets.newLinkedHashSet();

    public void add(final Repository repository) {
      dispatched.add(repository.getName());
    }

    public boolean contains(final Repository repository) {
      return dispatched.contains(repository.getName());
    }

    @Override
    public String toString() {
      return dispatched.toString();
    }

    /**
     * Get dispatched repositories names
     *
     * @return Unmodifiable {@link Set} of Dispatched repository names.
     */
    public Set<String> getDispatched() {
      return unmodifiableSet(dispatched);
    }
  }

  @Nonnull
  @Override
  public Response handle(@Nonnull final Context context) throws Exception {
    final String method = context.getRequest().getAction();
    switch (method) {
      case GET:
      case HEAD: {
        final DispatchedRepositories dispatched = context.getRequest()
            .getAttributes()
            .getOrCreate(DispatchedRepositories.class);
        return doGet(context, dispatched);
      }

      default:
        return HttpResponses.methodNotAllowed(method, GET, HEAD);
    }
  }

  /**
   * Method that actually performs group GET. Override if needed.
   */
  protected Response doGet(
      @Nonnull final Context context,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    final GroupFacet groupFacet = context.getRepository().facet(GroupFacet.class);
    return getFirst(context, groupFacet.members(), dispatched);
  }

  /**
   * Returns the first OK response from member repositories or {@link HttpResponses#notFound()} if none of the members
   * responded with OK.
   * 
   * Uses Virtual Threads to dispatch requests to member repositories concurrently.
   */
  protected Response getFirst(
      @Nonnull final Context context,
      @Nonnull final List<Repository> members,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    if (members.isEmpty()) {
      return notFoundResponse(context);
    }

    final Request request = context.getRequest();
    
    // Filter out repositories we've already dispatched to
    List<Repository> eligibleMembers = members.stream()
        .filter(member -> !dispatched.contains(member))
        .toList();
    
    if (eligibleMembers.isEmpty()) {
      return notFoundResponse(context);
    }

    // Add all members to dispatched set to prevent circular dispatch
    eligibleMembers.forEach(dispatched::add);

    // Create a CompletableFuture for each member repository request
    List<CompletableFuture<Response>> futures = eligibleMembers.stream()
        .map(member -> CompletableFuture.supplyAsync(() -> {
          try {
            log.trace("Trying member: {}", member);
            final ViewFacet view = member.facet(ViewFacet.class);
            final Response response = view.dispatch(request, context);
            log.trace("Member {} response {}", member, response.getStatus());
            return response;
          }
          catch (Exception e) {
            log.debug("Error dispatching to member {}: {}", member, e.getMessage());
            throw new RuntimeException(e);
          }
        }, VIRTUAL_THREAD_EXECUTOR))
        .toList();

    // Create a future that completes when any repository returns a valid response
    CompletableFuture<Response> firstValidResponse = anyValidResponse(futures);

    try {
      // Wait for the first valid response or until all futures complete
      Response response = firstValidResponse.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return response;
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while waiting for repository responses", e);
      return notFoundResponse(context);
    }
    catch (ExecutionException | TimeoutException e) {
      log.debug("Error or timeout getting repository responses", e);
      return notFoundResponse(context);
    }
  }

  /**
   * Creates a CompletableFuture that completes with the first valid response from any of the given futures.
   * If no valid responses are found, it completes with a not found response.
   */
  private CompletableFuture<Response> anyValidResponse(List<CompletableFuture<Response>> futures) {
    CompletableFuture<Response> result = new CompletableFuture<>();
    
    // Count how many futures we're waiting for
    final int[] remaining = {futures.size()};
    
    // For each future, when it completes, check if it's a valid response
    for (CompletableFuture<Response> future : futures) {
      future.whenComplete((response, throwable) -> {
        if (!result.isDone()) {
          if (throwable == null && isValidResponse(response)) {
            // We found a valid response, complete the result future
            result.complete(response);
          }
          else {
            // Decrement the counter of remaining futures
            remaining[0]--;
            if (remaining[0] == 0) {
              // All futures completed without a valid response
              result.complete(null); // Will be converted to notFoundResponse
            }
          }
        }
      });
    }
    
    return result;
  }

  /**
   * Returns all responses from all members as a linked map, where order is group member order.
   * 
   * Uses Virtual Threads to dispatch requests to member repositories concurrently.
   */
  protected LinkedHashMap<Repository, Response> getAll(
      @Nonnull final Context context,
      @Nonnull final Iterable<Repository> members,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    return getAll(context.getRequest(), context, members, dispatched);
  }

  /**
   * Similar to {@link #getAll(Context, Iterable, DispatchedRepositories)}, but allows for using a
   * different request then provided by the {@link Context#getRequest()} while still using the
   * same {@link Context} to execute the request in.
   *
   * Uses Virtual Threads to dispatch requests to member repositories concurrently.
   *
   * @param request {@link Request} that could be different then the {@link Context#getRequest()}
   * @param context {@link Context}
   * @param members {@link Repository}'s
   * @param dispatched {@link DispatchedRepositories}
   * @return LinkedHashMap of all responses from all members where order is group member order.
   * @throws Exception throw for any issues dispatching the request
   */
  protected LinkedHashMap<Repository, Response> getAll(
      @Nonnull final Request request,
      @Nonnull final Context context,
      @Nonnull final Iterable<Repository> members,
      @Nonnull final DispatchedRepositories dispatched) throws Exception
  {
    final LinkedHashMap<Repository, Response> responses = Maps.newLinkedHashMap();
    
    // Filter out repositories we've already dispatched to
    List<Repository> eligibleMembers = StreamSupport.stream(members.spliterator(), false)
        .filter(member -> !dispatched.contains(member))
        .toList();
    
    if (eligibleMembers.isEmpty()) {
      return responses;
    }

    // Add all members to dispatched set to prevent circular dispatch
    eligibleMembers.forEach(dispatched::add);

    // Create a CompletableFuture for each member repository request
    List<CompletableFuture<Map.Entry<Repository, Response>>> futures = eligibleMembers.stream()
        .map(member -> CompletableFuture.supplyAsync(() -> {
          try {
            log.trace("Trying member: {}", member);
            final ViewFacet view = member.facet(ViewFacet.class);
            final Response response = view.dispatch(request, context);
            log.trace("Member {} response {}", member, response.getStatus());
            return Map.entry(member, response);
          }
          catch (Exception e) {
            log.debug("Error dispatching to member {}: {}", member, e.getMessage());
            throw new RuntimeException(e);
          }
        }, VIRTUAL_THREAD_EXECUTOR))
        .toList();

    // Wait for all futures to complete
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      // Collect all responses in order
      for (CompletableFuture<Map.Entry<Repository, Response>> future : futures) {
        Map.Entry<Repository, Response> entry = future.get();
        responses.put(entry.getKey(), entry.getValue());
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Interrupted while waiting for repository responses", e);
    }
    catch (ExecutionException | TimeoutException e) {
      log.debug("Error or timeout getting repository responses", e);
      // Return any responses we did get
      for (CompletableFuture<Map.Entry<Repository, Response>> future : futures) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
          try {
            Map.Entry<Repository, Response> entry = future.get();
            responses.put(entry.getKey(), entry.getValue());
          }
          catch (Exception ignored) {
            // Skip this entry if there was an error
          }
        }
      }
    }

    return responses;
  }

  /**
   * Returns standard 404 with no message. Override for format specific messaging.
   */
  protected Response notFoundResponse(final Context context) {
    return HttpResponses.notFound();
  }

  /**
   * Validates if a response should be considered valid for group handler processing.
   * Uses pattern matching for switch to handle different response types.
   *
   * @param response the response to validate
   * @return true if the response is valid, false otherwise
   */
  protected boolean isValidResponse(final Response response) {
    if (response == null) {
      return false;
    }
    
    return switch (response) {
      // Case for successful responses
      case Response r when r.getStatus().isSuccessful() -> true;
      
      // Case for responses with USE_DISPATCHED_RESPONSE attribute
      case Response r when r.getAttributes().contains(USE_DISPATCHED_RESPONSE) -> true;
      
      // Case for responses with bypass header
      case Response r when BYPASS_HTTP_ERRORS_HEADER_VALUE.equals(r.getHeaders().get(BYPASS_HTTP_ERRORS_HEADER_NAME)) -> true;
      
      // Default case for any other response
      default -> false;
    };
  }
}