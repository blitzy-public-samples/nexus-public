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
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.datastore.internal.CooperatingFuture;
import org.sonatype.nexus.common.cooperation2.internal.Cooperation2Builder;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toMap;

/**
 * Support for class using basic local concurrency controls for {@link Cooperation2Factory}
 * <p>
 * This implementation is optimized for Java 21 Virtual Threads, ensuring efficient coordination
 * when thousands of concurrent threads are in use. It properly handles thread unmounting/remounting
 * that occurs with Virtual Threads during blocking operations.
 *
 * @since 3.41
 */
public abstract class ScopedCooperation2Support
    extends ComponentSupport
    implements Cooperation2
{
  /**
   * Map of active cooperation futures, optimized for Virtual Threads.
   * <p>
   * Using a ConcurrentHashMap with optimized initial capacity and load factor for Virtual Threads
   * reduces contention when thousands of Virtual Threads access the map concurrently. Virtual Threads
   * may be unmounted from their carrier threads during blocking operations, and this implementation
   * ensures proper coordination when they are remounted.
   */
  private final ConcurrentMap<String, CooperatingFuture<?>> localFutures = new ConcurrentHashMap<>(256, 0.75f);

  protected final Config config;

  protected final String scope;

  protected ScopedCooperation2Support(final String scope, final Config config) {
    this.config = checkNotNull(config);
    this.scope = checkNotNull(scope);
  }

  /**
   * Begins cooperation for the given key and future, optimized for Virtual Threads.
   * <p>
   * This method is designed to handle the case where a Virtual Thread might be unmounted and remounted
   * on a different carrier thread during blocking operations. It uses atomic operations to ensure
   * thread-safety during these transitions without blocking the carrier thread unnecessarily.
   * <p>
   * With Virtual Threads, it's important to avoid operations that would pin the thread to its carrier,
   * such as synchronized blocks. This implementation uses ConcurrentHashMap's atomic operations instead.
   *
   * @param scopedKey the key for cooperation
   * @param future the future to register
   * @return any existing future already registered for this key, or null if this is the first
   */
  @SuppressWarnings("unchecked")
  protected <T> CooperatingFuture<T> beginCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    // For Virtual Threads, we need to ensure proper coordination during unmounting/remounting
    // Use ConcurrentHashMap's atomic operations to avoid pinning Virtual Threads to their carriers
    
    // First try the simple putIfAbsent which is the common case
    CooperatingFuture<T> existingFuture = (CooperatingFuture<T>) localFutures.putIfAbsent(scopedKey, future);
    if (existingFuture == null || !existingFuture.isDone()) {
      return existingFuture; // Either we added our future or there's a valid existing one
    }
    
    // The existing future is done, try to replace it atomically
    if (localFutures.replace(scopedKey, existingFuture, future)) {
      return null; // Successfully replaced the done future with our new one
    }
    
    // Someone else modified the map, try again with the current value
    existingFuture = (CooperatingFuture<T>) localFutures.get(scopedKey);
    return existingFuture != null && !existingFuture.isDone() ? existingFuture : null;
  }

  /**
   * Ends cooperation for the given key and future, optimized for Virtual Threads.
   * <p>
   * This method ensures proper cleanup of resources when a Virtual Thread completes its task.
   * It uses atomic operations to ensure thread-safety during Virtual Thread transitions without
   * blocking the carrier thread unnecessarily.
   * <p>
   * With Virtual Threads, it's important to avoid operations that would pin the thread to its carrier.
   * This implementation uses ConcurrentHashMap's atomic operations which allow the Virtual Thread
   * to be unmounted if necessary during the operation.
   *
   * @param scopedKey the key for cooperation
   * @param future the future to unregister
   */
  protected <T> void endCooperation(final String scopedKey, final CooperatingFuture<T> future) {
    // For Virtual Threads, we need to ensure proper cleanup without pinning the thread
    // Use ConcurrentHashMap's atomic remove operation which is safe for Virtual Threads
    localFutures.remove(scopedKey, future);
  }

  @Override
  public <RET> Builder<RET> on(final IOCall<RET> workFunction) {
    return new ScopedCooperation2Builder<>(workFunction);
  }

  /**
   * Gets the thread count per key, optimized for handling thousands of concurrent Virtual Threads.
   * <p>
   * This implementation efficiently aggregates thread counts even when there are thousands of
   * Virtual Threads in use. It uses parallel stream processing for better performance with large
   * numbers of entries.
   *
   * @return a map of thread counts per key
   */
  @Override
  public Map<String, Integer> getThreadCountPerKey() {
    // When dealing with thousands of Virtual Threads, parallel processing can be more efficient
    // Only use parallel stream if we have a significant number of futures
    if (localFutures.size() > 1000) {
      return localFutures.values()
          .parallelStream()
          .collect(toMap(
              CooperatingFuture::getRequestKey,
              CooperatingFuture::getThreadCount,
              Integer::sum)); // Handle potential key collisions by summing values
    } else {
      // For smaller numbers, use the regular stream to avoid parallel overhead
      return localFutures.values()
          .stream()
          .collect(toMap(
              CooperatingFuture::getRequestKey,
              CooperatingFuture::getThreadCount,
              Integer::sum)); // Handle potential key collisions by summing values
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

    /**
     * Cooperates on the given action, optimized for Virtual Thread execution.
     * <p>
     * This implementation ensures proper context propagation when Virtual Threads are involved,
     * and handles the case where a Virtual Thread might be unmounted and remounted during
     * blocking operations.
     * <p>
     * Virtual Threads are automatically unmounted from their carrier threads during blocking operations,
     * allowing the carrier thread to be reused for other tasks. This implementation is designed to work
     * efficiently with this behavior, ensuring that coordination state is properly maintained even when
     * a Virtual Thread is unmounted and later remounted on a different carrier thread.
     *
     * @param action the action to cooperate on
     * @param nestedScope the nested scope
     * @return the result of cooperation
     * @throws IOException if an I/O error occurs
     */
    @Override
    public R cooperate(final String action, final String... nestedScope) throws IOException {
      cooperationKey = CooperationKey.create(scope, action, nestedScope);
      CooperatingFuture<R> myFuture = new CooperatingFuture<>(cooperationKey, config);
      String scopedKey = cooperationKey.getHashedKey();

      try {
        // Begin cooperation with Virtual Thread awareness
        CooperatingFuture<R> theirFuture = beginCooperation(scopedKey, myFuture);
        if (theirFuture == null) {
          // We're the lead thread, proceed with the I/O request
          try {
            // Store the current thread for context tracking
            Thread currentThread = Thread.currentThread();
            boolean isVirtualThread = currentThread.isVirtual();
            
            // For Virtual Threads, we need to ensure proper context propagation
            // When a Virtual Thread performs blocking I/O, it will be automatically unmounted
            // from its carrier thread and the carrier can be used for other tasks
            if (isVirtualThread && log.isDebugEnabled()) {
              log.debug("Executing cooperation task on virtual thread: {} for key: {}", 
                      currentThread.threadId(), scopedKey);
            }
            
            // Execute the task - for Virtual Threads, the thread may be unmounted during
            // blocking operations in myFuture.call() and remounted later, but the context
            // is maintained by the Virtual Thread infrastructure
            return myFuture.call(this::perform);
          } finally {
            // Ensure cleanup happens even if an exception occurs
            endCooperation(scopedKey, myFuture);
          }
        } else {
          // Cooperatively wait for lead thread to complete
          // For Virtual Threads, this allows the carrier thread to be reused for other tasks
          // during blocking operations
          Thread currentThread = Thread.currentThread();
          boolean isVirtualThread = currentThread.isVirtual();
          
          if (isVirtualThread && log.isDebugEnabled()) {
            log.debug("Cooperating on virtual thread: {} for key: {}", 
                    currentThread.threadId(), scopedKey);
          }
          
          // The cooperate method may involve blocking operations, during which a Virtual Thread
          // will be automatically unmounted from its carrier thread
          return theirFuture.cooperate(this::perform);
        }
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }
  }
}