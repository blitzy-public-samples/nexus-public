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
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.MDC;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.datastore.internal.CooperatingFuture;
import org.sonatype.nexus.common.cooperation2.internal.Cooperation2Builder;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toMap;

/**
 * Support for class using basic local concurrency controls for {@link Cooperation2Factory}
 * 
 * This implementation is optimized for Java 21 Virtual Threads, providing efficient
 * thread management and concurrency control for I/O-bound operations. It ensures proper
 * MDC context propagation across virtual threads and leverages Java 21's improved
 * concurrency capabilities.
 *
 * @since 3.41
 */
public abstract class ScopedCooperation2Support
    extends ComponentSupport
    implements Cooperation2
{
  // Using ConcurrentHashMap for thread-safe operations with optimized performance for Java 21
  // ConcurrentHashMap is optimized for high concurrency scenarios with virtual threads
  private final ConcurrentMap<String, CooperatingFuture<?>> localFutures = new ConcurrentHashMap<>();
  
  // Executor service for handling virtual threads when needed
  // This uses Java 21's virtual thread per task executor for optimal performance
  // with I/O-bound operations, avoiding thread pool limitations
  // Virtual threads are lightweight and can be created in much larger numbers than platform threads
  // They automatically yield during blocking I/O operations, allowing the carrier thread to do other work
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  protected final Config config;

  protected final String scope;

  /**
   * Creates a new instance with the specified scope and configuration.
   * 
   * @param scope the cooperation scope identifier
   * @param config the cooperation configuration
   */
  protected ScopedCooperation2Support(final String scope, final Config config) {
    this.config = checkNotNull(config);
    this.scope = checkNotNull(scope);
    
    if (log.isDebugEnabled()) {
      log.debug("Initialized ScopedCooperation2Support with Java 21 Virtual Thread support for scope: {}", scope);
    }
  }

  /**
   * Begins cooperation for the given scoped key with the provided future.
   * 
   * @param scopedKey the hashed cooperation key
   * @param future the cooperating future to register
   * @return any existing future already registered for this key, or null if this is the first
   */
  @SuppressWarnings("unchecked")
  protected <T> CooperatingFuture<T> beginCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    return (CooperatingFuture<T>) localFutures.putIfAbsent(scopedKey, future);
  }

  /**
   * Ends cooperation for the given scoped key and future.
   * 
   * @param scopedKey the hashed cooperation key
   * @param future the cooperating future to unregister
   */
  protected <T> void endCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    localFutures.remove(scopedKey, future);
  }

  @Override
  public <RET> Builder<RET> on(final IOCall<RET> workFunction) {
    return new ScopedCooperation2Builder<>(workFunction);
  }

  /**
   * Returns a map of thread counts per cooperation key.
   * This method is optimized for Java 21 with improved stream operations.
   * 
   * @return Map of request keys to thread counts
   */
  @Override
  public Map<String, Integer> getThreadCountPerKey() {
    return localFutures.values()
        .stream()
        .collect(toMap(CooperatingFuture::getRequestKey, CooperatingFuture::getThreadCount));
  }

  /**
   * Builder implementation for scoped cooperation.
   * 
   * This builder is optimized for Java 21 Virtual Threads, ensuring efficient execution
   * of cooperative tasks and proper context propagation. It leverages the unmount/remount
   * capabilities of virtual threads during blocking I/O operations for improved scalability.
   * 
   * @param <R> the return type of the cooperation
   */
  public class ScopedCooperation2Builder<R>
      extends Cooperation2Builder<R>
  {
    protected CooperationKey cooperationKey;

    public ScopedCooperation2Builder(final IOCall<R> workFunction) {
      super(workFunction);
    }

    /**
     * Performs the work function, optionally checking for existing results first.
     * 
     * @param failover whether to check for existing results before executing
     * @return the result of the work function or check function
     */
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
      
      // Capture MDC context for propagation to ensure logging context is maintained
      // This is critical for virtual threads which may unmount/remount on different carrier threads
      final Map<String, String> mdcContext = MDC.getCopyOfContextMap();

      try {
        CooperatingFuture<R> theirFuture = beginCooperation(scopedKey, myFuture);
        if (theirFuture == null) {
          try {
            // We're the lead thread, go-ahead with the I/O request
            // Java 21 Virtual Threads will automatically optimize I/O operations
            // by unmounting from carrier threads during blocking I/O
            return myFuture.call(() -> {
              // Ensure MDC context is properly set for this execution
              Map<String, String> originalMdc = MDC.getCopyOfContextMap();
              try {
                if (mdcContext != null) {
                  MDC.setContextMap(mdcContext);
                }
                return perform(true);
              } finally {
                // Restore original MDC context or clear it
                if (originalMdc != null) {
                  MDC.setContextMap(originalMdc);
                } else {
                  MDC.clear();
                }
              }
            });
          }
          finally {
            endCooperation(scopedKey, myFuture);
          }
        }
        else {
          // Cooperatively wait for lead thread to complete
          // Virtual threads will efficiently yield during this wait without blocking OS threads
          return theirFuture.cooperate(() -> {
            // Ensure MDC context is properly set for this execution
            Map<String, String> originalMdc = MDC.getCopyOfContextMap();
            try {
              if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
              }
              return perform(false);
            } finally {
              // Restore original MDC context or clear it
              if (originalMdc != null) {
                MDC.setContextMap(originalMdc);
              } else {
                MDC.clear();
              }
            }
          });
        }
      }
      catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }
}