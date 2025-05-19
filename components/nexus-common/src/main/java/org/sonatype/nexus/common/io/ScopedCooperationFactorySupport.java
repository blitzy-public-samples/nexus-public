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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.Cooperation.IOCheck;

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
  /**
   * Flag to determine if Virtual Threads should be used for I/O operations.
   * Defaults to true in Java 21 environments.
   */
  private boolean useVirtualThreads = true;

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
   * Creates a new {@link CooperatingFuture} optimized for Virtual Thread execution.
   * 
   * @param requestKey the unique key identifying this request
   * @param config the cooperation configuration
   * @param executor the Virtual Thread executor service to use
   * @return a new CooperatingFuture instance
   * @since 3.60
   */
  protected <T> CooperatingFuture<T> createVirtualThreadFuture(final String requestKey, 
                                                             final Config config,
                                                             final ExecutorService executor) {
    CooperatingFuture<T> future = createFuture(requestKey, config);
    future.setVirtualThreadExecutor(executor);
    return future;
  }

  /**
   * Creates a new {@link ExecutorService} that uses Virtual Threads.
   * Each task submitted to this executor will run in its own Virtual Thread.
   *
   * @return An ExecutorService that creates a new Virtual Thread for each task
   * @since 3.60
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Configures whether Virtual Threads should be used for I/O operations.
   *
   * @param useVirtualThreads true to use Virtual Threads, false to use platform threads
   * @return this factory for fluent API
   * @since 3.60
   */
  public ScopedCooperationFactorySupport useVirtualThreads(final boolean useVirtualThreads) {
    this.useVirtualThreads = useVirtualThreads;
    return this;
  }

  /**
   * Checks if Virtual Threads are enabled for this factory.
   *
   * @return true if Virtual Threads are enabled, false otherwise
   * @since 3.60
   */
  public boolean isUsingVirtualThreads() {
    return useVirtualThreads;
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
   * Join cache results without retrying; assumes that any caches have no lag.
   */
  @Nullable
  protected <T> T join(final IOCheck<T> request) throws IOException {
    return request.check();
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
     * Executor service for Virtual Thread operations, created lazily when needed.
     */
    private ExecutorService virtualThreadExecutor;

    /**
     * @param id unique identifier for this cooperation point
     */
    public ScopedCooperation(final String id, final Config config) {
      this.scope = checkNotNull(id) + ':';
      this.config = checkNotNull(config);
    }

    /**
     * Gets or creates the Virtual Thread executor service.
     * 
     * @return the Virtual Thread executor service
     */
    private synchronized ExecutorService getVirtualThreadExecutor() {
      if (virtualThreadExecutor == null && useVirtualThreads) {
        virtualThreadExecutor = createVirtualThreadExecutor();
      }
      return virtualThreadExecutor;
    }

    @Override
    public <T> T cooperate(final String requestKey, final IOCall<T> request) throws IOException {
      String scopedKey = scope + requestKey;
      CooperatingFuture<T> myFuture;
      
      // Create appropriate future based on Virtual Thread configuration
      if (useVirtualThreads) {
        ExecutorService executor = getVirtualThreadExecutor();
        myFuture = createVirtualThreadFuture(requestKey, config, executor);
      } else {
        myFuture = createFuture(requestKey, config);
      }

      CooperatingFuture<T> theirFuture = beginCooperation(scopedKey, myFuture);
      if (theirFuture == null) {
        try {
          // We're the lead thread, go ahead with the I/O request
          // If using Virtual Threads, the execution will be delegated to the Virtual Thread executor
          return myFuture.call(request);
        } finally {
          endCooperation(scopedKey, myFuture);
        }
      } else {
        // Cooperatively wait for lead thread to complete
        return theirFuture.cooperate(request);
      }
    }

    @Override
    public <T> T join(final IOCheck<T> request) throws IOException {
      return ScopedCooperationFactorySupport.this.join(request);
    }

    @Override
    public Map<String, Integer> getThreadCountPerKey() {
      return streamFutures(scope).collect(toMap(CooperatingFuture::getRequestKey, CooperatingFuture::getThreadCount));
    }
  }
}