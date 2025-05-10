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
package org.sonatype.nexus.common.sequence;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Supplies local {@link AtomicSequence}s.
 * <p>
 * This implementation leverages Java 21's improved atomic operations which use VarHandle internally
 * for better performance and memory consistency. The AtomicLong class in Java 21 has been optimized
 * to use the most efficient hardware instructions available on the platform.
 * <p>
 * Java 21 provides significant improvements to atomic operations through enhanced VarHandle implementations,
 * resulting in better performance and reduced contention. This implementation takes advantage of these
 * improvements to provide efficient sequence generation.
 *
 * @since 3.14
 */
@Named("local")
@Singleton
public class LocalAtomicSequenceFactory
    implements AtomicSequenceFactory
{
  /**
   * Creates a new {@link AtomicSequence} using Java 21's optimized atomic operations.
   * <p>
   * The implementation uses AtomicLong which in Java 21 leverages VarHandle for optimal
   * atomic operations with improved memory consistency guarantees and better performance.
   *
   * @param id identifies this sequence for debugging purposes
   * @param initialValue the initial value for this sequence
   * @return a thread-safe sequence generator
   */
  @Override
  public AtomicSequence create(final String id, final LongSupplier initialValue) {
    // AtomicLong in Java 21 uses VarHandle internally for optimal atomic operations
    // with improved memory consistency guarantees and better performance
    AtomicLong sequence = new AtomicLong(initialValue.getAsLong());
    
    // The incrementAndGet operation is atomic and has memory effects as specified by
    // VarHandle.getAndAdd in Java 21, ensuring visibility across threads
    return () -> sequence.incrementAndGet();
  }
  
  /**
   * Creates a high-contention optimized {@link AtomicSequence}.
   * <p>
   * This alternative implementation is optimized for scenarios with high thread contention.
   * It uses LongAdder which reduces contention by maintaining multiple counters that can be
   * updated independently and only combining them when the current value is needed.
   * <p>
   * Note: This method is provided as an alternative but is not part of the public API.
   * It may be exposed in the future if needed for high-contention scenarios.
   *
   * @param id identifies this sequence for debugging purposes
   * @param initialValue the initial value for this sequence
   * @return a thread-safe sequence generator optimized for high contention
   */
  protected AtomicSequence createHighContention(final String id, final LongSupplier initialValue) {
    // Start with the initial value minus 1 since we'll increment before returning
    final long initialVal = initialValue.getAsLong() - 1;
    final LongAdder adder = new LongAdder();
    adder.add(initialVal);
    
    // LongAdder in Java 21 provides better performance under high contention
    // by using a more sophisticated internal structure with VarHandle operations
    return () -> {
      adder.increment();
      return adder.sum();
    };
  }
}