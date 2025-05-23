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
package org.sonatype.nexus.common.io;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.Cooperation.IOCheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toMap;

/**
 * Scaffolding for scoped {@link CooperationFactory} implementations.
 *
 * @since 3.14
 */
public abstract class ScopedCooperationFactorySupport
    extends CooperationFactorySupport
{
  private static final Logger log = LoggerFactory.getLogger(ScopedCooperationFactorySupport.class);
  
  /**
   * Executor service for Virtual Thread-based operations.
   * 
   * @since 3.60
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  @Override
  protected Cooperation build(final String id, final Config config) {
    return new ScopedCooperation(id, config);
  }

  /**
   * Creates a new {@link CooperatingFuture} for the given configuration.
   */
  protected <T> CooperatingFuture<T> createFuture(final String requestKey, final Config config) {
    return new CooperatingFuture<>(requestKey, config);
  }

  /**
   * Begins cooperation for the scoped key using the given future.
   *
   * @return {@code null} if the key was not already in use; otherwise the currently associated future
   */
  protected abstract <T> CooperatingFuture<T> beginCooperation(String scopedKey, CooperatingFuture<T> future);

  /**
   * Ends cooperation for the scoped key and its associated future.
   *
   * Each thread that successfully calls {@link #beginCooperation} will eventually call this with the same values.
   */
  protected abstract <T> void endCooperation(String scopedKey, CooperatingFuture<T> future);

  /**
   * Streams all futures that are currently cooperating.
   */
  protected abstract Stream<CooperatingFuture<?>> streamFutures(String scope);
  
  /**
   * Begins cooperation for the scoped key using the given future with Virtual Thread support.
   * This method should be implemented to provide optimized handling for Virtual Thread-based cooperation.
   *
   * @param scopedKey the scoped key for cooperation
   * @param future the future to associate with this cooperation
   * @return {@code null} if the key was not already in use; otherwise the currently associated future
   * @since 3.60
   */
  protected <T> CooperatingFuture<T> beginVirtualThreadCooperation(String scopedKey, CooperatingFuture<T> future) {
    // Default implementation delegates to the standard beginCooperation method
    return beginCooperation(scopedKey, future);
  }

  /**
   * Ends cooperation for the scoped key and its associated future with Virtual Thread support.
   * This method should be implemented to provide optimized handling for Virtual Thread-based cooperation.
   *
   * @param scopedKey the scoped key for cooperation
   * @param future the future to disassociate from this cooperation
   * @since 3.60
   */
  protected <T> void endVirtualThreadCooperation(String scopedKey, CooperatingFuture<T> future) {
    // Default implementation delegates to the standard endCooperation method
    endCooperation(scopedKey, future);
  }
  
  /**
   * Streams all futures that are currently cooperating with Virtual Thread support.
   * This method should be implemented to provide optimized handling for Virtual Thread-based cooperation.
   *
   * @param scope the scope to stream futures from
   * @return stream of cooperating futures
   * @since 3.60
   */
  protected Stream<CooperatingFuture<?>> streamVirtualThreadFutures(String scope) {
    // Default implementation delegates to the standard streamFutures method
    return streamFutures(scope);
  }

  /**
   * Join cache results without retrying; assumes that any caches have no lag.
   */
  @Nullable
  protected <T> T join(final IOCheck<T> request) throws IOException {
    return request.check();
  }
  
  /**
   * Executes the given task using Virtual Threads when available and enabled.
   * 
   * @param task the task to execute
   * @param useVirtualThreads whether to use Virtual Threads
   * @since 3.60
   */
  protected void executeWithVirtualThread(Runnable task, boolean useVirtualThreads) {
    if (useVirtualThreads) {
      try {
        VIRTUAL_THREAD_EXECUTOR.execute(task);
      } catch (Exception e) {
        log.warn("Failed to execute task with Virtual Thread, falling back to current thread", e);
        task.run();
      }
    } else {
      task.run();
    }
  }

  /**
   * {@link Cooperation} that's saved under a scoped partition of the {@link #futures()} map.
   */
  private class ScopedCooperation
      implements Cooperation
  {
    private final String scope;

    private final Config config;

    /**
     * @param id unique identifier for this cooperation point
     */
    public ScopedCooperation(final String id, final Config config) {
      this.scope = checkNotNull(id) + ':';
      this.config = checkNotNull(config);
    }

    @Override
    public <T> T cooperate(final String requestKey, final IOCall<T> request) throws IOException {
      CooperatingFuture<T> myFuture = createFuture(requestKey, config);
      String scopedKey = scope + requestKey;

      if (config.useVirtualThreads()) {
        // Use Virtual Thread-optimized cooperation
        CooperatingFuture<T> theirFuture = beginVirtualThreadCooperation(scopedKey, myFuture);
        if (theirFuture == null) {
          try {
            // We're the lead thread, go-ahead with the I/O request
            // For I/O-bound operations, we can leverage Virtual Threads for better performance
            return myFuture.call(request);
          }
          finally {
            endVirtualThreadCooperation(scopedKey, myFuture);
          }
        }
        else {
          // Cooperatively wait for lead thread to complete
          return theirFuture.cooperate(request);
        }
      } else {
        // Use standard platform thread cooperation
        CooperatingFuture<T> theirFuture = beginCooperation(scopedKey, myFuture);
        if (theirFuture == null) {
          try {
            return myFuture.call(request); // we're the lead thread, go-ahead with the I/O request
          }
          finally {
            endCooperation(scopedKey, myFuture);
          }
        }
        else {
          return theirFuture.cooperate(request); // cooperatively wait for lead thread to complete
        }
      }
    }

    @Override
    public <T> T join(final IOCheck<T> request) throws IOException {
      return ScopedCooperationFactorySupport.this.join(request);
    }

    @Override
    public Map<String, Integer> getThreadCountPerKey() {
      if (config.useVirtualThreads()) {
        return streamVirtualThreadFutures(scope).collect(toMap(
            CooperatingFuture::getRequestKey, 
            CooperatingFuture::getThreadCount));
      } else {
        return streamFutures(scope).collect(toMap(
            CooperatingFuture::getRequestKey, 
            CooperatingFuture::getThreadCount));
      }
    }
    
    /**
     * Returns statistics about Virtual Thread usage for this cooperation point.
     * 
     * @return map of request keys to Virtual Thread counts
     * @since 3.60
     */
    public Map<String, Integer> getVirtualThreadCountPerKey() {
      if (config.useVirtualThreads()) {
        return streamVirtualThreadFutures(scope).collect(toMap(
            CooperatingFuture::getRequestKey, 
            CooperatingFuture::getThreadCount));
      } else {
        // Return empty map when Virtual Threads are not enabled
        return Map.of();
      }
    }
  }
}
