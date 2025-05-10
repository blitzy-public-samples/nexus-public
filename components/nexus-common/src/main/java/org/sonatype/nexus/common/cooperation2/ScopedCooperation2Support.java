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
package org.sonatype.nexus.common.cooperation2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.datastore.internal.CooperatingFuture;
import org.sonatype.nexus.common.cooperation2.internal.Cooperation2Builder;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toMap;

/**
 * Support for class using basic local concurrency controls for {@link Cooperation2Factory}
 * 
 * This implementation is optimized for Java 21 Virtual Threads, providing efficient
 * cooperative execution for I/O-bound operations. Virtual Threads allow for high concurrency
 * with minimal resource overhead, making them ideal for cooperative I/O operations.
 *
 * @since 3.41
 */
public abstract class ScopedCooperation2Support
    extends ComponentSupport
    implements Cooperation2
{
  private final ConcurrentMap<String, CooperatingFuture<?>> localFutures = new ConcurrentHashMap<>();
  
  /**
   * Virtual thread executor for handling I/O-bound cooperative operations.
   * Using virtual threads eliminates the need for traditional thread pool sizing
   * and provides optimal throughput for I/O operations.
   */
  private final AtomicReference<ExecutorService> virtualThreadExecutor = 
      new AtomicReference<>(Executors.newVirtualThreadPerTaskExecutor());

  protected final Config config;

  protected final String scope;

  protected ScopedCooperation2Support(final String scope, final Config config) {
    this.config = checkNotNull(config);
    this.scope = checkNotNull(scope);
  }

  @SuppressWarnings("unchecked")
  protected <T> CooperatingFuture<T> beginCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    return (CooperatingFuture<T>) localFutures.putIfAbsent(scopedKey, future);
  }

  protected <T> void endCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    localFutures.remove(scopedKey, future);
  }
  
  /**
   * Submits a task to be executed by a virtual thread.
   * This method leverages Java 21 Virtual Threads for optimal I/O performance.
   *
   * @param task the runnable task to execute
   */
  protected void submitVirtualThreadTask(Runnable task) {
    virtualThreadExecutor.get().submit(task);
  }

  @Override
  public <RET> Builder<RET> on(final IOCall<RET> workFunction) {
    return new ScopedCooperation2Builder<>(workFunction);
  }

  @Override
  public Map<String, Integer> getThreadCountPerKey() {
    return localFutures.values()
        .stream()
        .collect(toMap(CooperatingFuture::getRequestKey, CooperatingFuture::getThreadCount));
  }
  
  /**
   * Shuts down the virtual thread executor.
   * This method should be called when this component is being disposed.
   */
  protected void shutdown() {
    ExecutorService executor = virtualThreadExecutor.getAndSet(null);
    if (executor != null) {
      executor.shutdown();
    }
  }

  public class ScopedCooperation2Builder<R>
      extends Cooperation2Builder<R>
  {
    protected CooperationKey cooperationKey;

    public ScopedCooperation2Builder(final IOCall<R> workFunction) {
      super(workFunction);
    }

    protected R perform(final Boolean failover) {
      try {
        Optional<R> potentialResult;
        if (failover && (potentialResult = checkFunction.check()).isPresent()) {
          return potentialResult.get();
        }

        return workFunction.call();
      }
      catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public R cooperate(final String action, final String... nestedScope) throws IOException {
      cooperationKey = CooperationKey.create(scope, action, nestedScope);
      CooperatingFuture<R> myFuture = new CooperatingFuture<>(cooperationKey, config);
      String scopedKey = cooperationKey.getHashedKey();

      try {
        CooperatingFuture<R> theirFuture = beginCooperation(scopedKey, myFuture);
        if (theirFuture == null) {
          try {
            // We're the lead thread, go-ahead with the I/O request
            // Virtual threads excel at handling I/O operations efficiently
            return myFuture.call(this::perform);
          }
          finally {
            endCooperation(scopedKey, myFuture);
          }
        }
        else {
          // Cooperatively wait for lead thread to complete
          // Virtual threads can efficiently wait without blocking OS threads
          return theirFuture.cooperate(this::perform);
        }
      }
      catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }
}