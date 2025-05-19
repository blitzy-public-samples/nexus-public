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
package org.sonatype.nexus.common.cooperation2.datastore.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.sonatype.nexus.common.cooperation2.Config;
import org.sonatype.nexus.common.cooperation2.Cooperation2Factory;
import org.sonatype.nexus.common.cooperation2.IOCall;
import org.sonatype.nexus.common.cooperation2.ScopedCooperation2Support;
import org.sonatype.nexus.common.thread.VirtualThreadContextCarrier;

/**
 * An implementation of {@link Cooperation2Factory} which uses local concurrency controls
 * with optimizations for Java 21 Virtual Threads.
 * <p>
 * This implementation leverages Virtual Threads for I/O operations, providing improved
 * performance and scalability. Virtual Threads are lightweight threads managed by the JVM
 * rather than the OS, allowing for much higher concurrency with minimal overhead.
 * <p>
 * Key optimizations include:
 * <ul>
 *   <li>Using Virtual Threads for I/O-bound operations</li>
 *   <li>Proper context propagation across thread boundaries</li>
 *   <li>Thread-aware cooperation pooling</li>
 *   <li>Performance optimizations for thread scheduling</li>
 * </ul>
 *
 * @since 3.41
 */
public class LocalCooperation2
    extends ScopedCooperation2Support
{
  /**
   * Flag indicating whether Virtual Threads are supported by the current JVM.
   */
  private final boolean virtualThreadsSupported;
  
  /**
   * Flag indicating whether Virtual Threads should be used for I/O operations.
   */
  private final boolean useVirtualThreads;

  /**
   * Creates a new LocalCooperation2 instance with the specified scope and configuration.
   *
   * @param scope the cooperation scope
   * @param config the cooperation configuration
   */
  public LocalCooperation2(final String scope, final Config config) {
    super(scope, config);
    this.virtualThreadsSupported = VirtualThreadCooperationPool.isVirtualThreadSupported();
    this.useVirtualThreads = virtualThreadsSupported && Boolean.getBoolean("nexus.cooperation.useVirtualThreads");
    
    if (useVirtualThreads && log.isDebugEnabled()) {
      log.debug("LocalCooperation2 initialized with Virtual Thread support for scope: {}", scope);
    }
  }
  
  /**
   * Creates a builder for configuring cooperation on the given I/O call,
   * with optimizations for Virtual Threads.
   *
   * @param workFunction the I/O call to cooperate on
   * @return a builder for configuring cooperation
   */
  @Override
  public <RET> Builder<RET> on(final IOCall<RET> workFunction) {
    return new VirtualThreadAwareScopedCooperation2Builder<>(workFunction);
  }
  
  /**
   * A builder for configuring cooperation with Virtual Thread awareness.
   *
   * @param <R> the return type of the cooperation
   */
  private class VirtualThreadAwareScopedCooperation2Builder<R>
      extends ScopedCooperation2Builder<R>
  {
    /**
     * Flag indicating whether the operation is I/O-bound and suitable for Virtual Threads.
     */
    private boolean isIOBound = true;
    
    /**
     * Creates a new builder for the given work function.
     *
     * @param workFunction the work function to cooperate on
     */
    public VirtualThreadAwareScopedCooperation2Builder(final IOCall<R> workFunction) {
      super(workFunction);
    }
    
    /**
     * Marks this operation as CPU-bound, making it less suitable for Virtual Threads.
     *
     * @return this builder
     */
    public Builder<R> cpuBound() {
      this.isIOBound = false;
      return this;
    }
    
    /**
     * Performs the work function with the given failover flag.
     *
     * @param failover whether to use failover mode
     * @return the result of the work function
     */
    @Override
    protected R perform(final Boolean failover) {
      try {
        Optional<R> potentialResult;
        if (failover && (potentialResult = checkFunction.check()).isPresent()) {
          return potentialResult.get();
        }
        
        // For I/O-bound operations, consider using Virtual Threads
        if (useVirtualThreads && isIOBound) {
          // Execute the work function on a Virtual Thread with proper context propagation
          return VirtualThreadCooperationPool.submit(() -> {
            try {
              return workFunction.call();
            } 
            catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
        } else {
          // Use the standard approach for non-I/O operations or when Virtual Threads are not enabled
          return workFunction.call();
        }
      }
      catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    
    /**
     * Cooperates on the given action with Virtual Thread awareness.
     * <p>
     * This implementation ensures proper context propagation when Virtual Threads are involved,
     * and handles the case where a Virtual Thread might be unmounted and remounted during
     * blocking operations.
     *
     * @param action the action to cooperate on
     * @param nestedScope the nested scope
     * @return the result of cooperation
     * @throws IOException if an I/O error occurs
     */
    @Override
    public R cooperate(final String action, final String... nestedScope) throws IOException {
      // Store the current thread information for context tracking
      Thread currentThread = Thread.currentThread();
      boolean isVirtualThread = VirtualThreadContextCarrier.isCurrentThreadVirtual();
      
      if (isVirtualThread && log.isDebugEnabled()) {
        log.debug("Cooperating on virtual thread: {} for action: {}", 
                currentThread.threadId(), action);
      }
      
      // For Virtual Threads, ensure proper context propagation
      if (isVirtualThread) {
        // Use the context carrier to ensure proper context propagation
        return VirtualThreadContextCarrier.supplyWithContext(() -> {
          try {
            return super.cooperate(action, nestedScope);
          } 
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
      } else {
        // For platform threads, use the standard approach
        return super.cooperate(action, nestedScope);
      }
    }
  }
}
