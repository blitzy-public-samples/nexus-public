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
package org.sonatype.nexus.common.cooperation2.internal;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.cooperation2.Cooperation2;
import org.sonatype.nexus.common.cooperation2.CooperationKey;
import org.sonatype.nexus.common.cooperation2.IOCall;

import com.google.common.base.Stopwatch;

/**
 * An implementation of {@link Cooperation2} which doesn't use any concurrency control and each thread proceeds
 * individually. This is used when co-operation is disabled.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads as it doesn't use any thread management or
 * concurrency control mechanisms that would interfere with Virtual Thread operation. It works seamlessly with
 * both platform threads and virtual threads, preserving thread context and local variables across operations.
 * <p>
 * Key Java 21 compatibility features:
 * <ul>
 *   <li>No thread pinning - doesn't use operations that would pin virtual threads to carrier threads</li>
 *   <li>No thread pool management - avoids traditional thread pool sizing concerns</li>
 *   <li>Thread context preservation - maintains calling thread's context regardless of thread type</li>
 *   <li>No blocking synchronization - doesn't use synchronized blocks or other blocking primitives</li>
 * </ul>
 * <p>
 * Even though this implementation doesn't use concurrency control, it's important that it remains compatible
 * with Java 21 Virtual Threads as it may be called from contexts using virtual threads.
 *
 * @since 3.41
 */
public class DisabledCooperation2
    extends ComponentSupport
    implements Cooperation2
{
  private final String scope;

  /**
   * Constructor.
   *
   * @param scope the cooperation scope
   */
  public DisabledCooperation2(final String scope) {
    this.scope = scope;
  }

  /**
   * Creates a builder for the given work function.
   * <p>
   * This method is compatible with Java 21 Virtual Threads and can be safely called from any thread context.
   *
   * @param workFunction the function to execute
   * @return a builder for configuring cooperation
   */
  @Override
  public <RET> Builder<RET> on(final IOCall<RET> workFunction) {
    return new DisabledCooperation2Builder<>(workFunction);
  }

  /**
   * Returns an empty map since this implementation doesn't track thread counts.
   * <p>
   * This method is compatible with Java 21 Virtual Threads and can be safely called from any thread context.
   *
   * @return an empty map
   */
  @Override
  public Map<String, Integer> getThreadCountPerKey() {
    return Collections.emptyMap();
  }

  /**
   * Builder implementation that executes work functions directly without concurrency control.
   * <p>
   * This implementation is fully compatible with Java 21 Virtual Threads and preserves the calling thread's context.
   *
   * @param <R> the return type of the work function
   */
  private class DisabledCooperation2Builder<R>
      extends Cooperation2Builder<R>
  {
    /**
     * Constructor.
     *
     * @param workFunction the function to execute
     */
    DisabledCooperation2Builder(final IOCall<R> workFunction) {
      super(workFunction);
    }

    /**
     * Executes the work function directly without any concurrency control.
     * <p>
     * This implementation is fully compatible with Java 21 Virtual Threads as it:
     * <ul>
     *   <li>Preserves the calling thread's context (whether platform or virtual thread)</li>
     *   <li>Doesn't use any blocking operations that would pin virtual threads</li>
     *   <li>Doesn't use thread locals in a way that would interfere with virtual thread scheduling</li>
     * </ul>
     * 
     * @param action the action being performed
     * @param nestedScope optional nested scope
     * @return the result of the work function
     * @throws IOException if the work function throws an IOException
     */
    @Override
    public R cooperate(final String action, final String... nestedScope) throws IOException {
      CooperationKey cooperationKey = CooperationKey.create(scope, action, nestedScope);
      // Using standard SLF4J logging pattern which works with both platform and virtual threads
      log.debug("Starting work for {}", cooperationKey);
      Stopwatch timer = Stopwatch.createStarted();

      try {
        // Direct execution of the work function preserves the calling thread's context
        // This works seamlessly with both platform threads and virtual threads
        return workFunction.call();
      }
      finally {
        log.debug("Completed work for {} in {}", cooperationKey, timer.elapsed());
      }
    }
  }
}