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
import java.util.function.LongSupplier;

import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Supplies local {@link AtomicSequence}s using Java's atomic operations.
 * 
 * <p>This implementation leverages Java 21's optimized atomic operations for improved
 * performance and thread safety. It provides strong memory consistency guarantees
 * through the use of {@link AtomicLong}, ensuring visibility of updates across threads
 * including virtual threads.</p>
 *
 * @since 3.14
 */
@Named("local")
@Singleton
public class LocalAtomicSequenceFactory
    implements AtomicSequenceFactory
{
  @Override
  public AtomicSequence create(final String id, final LongSupplier initialValue) {
    // Create an AtomicLong with the supplied initial value
    // AtomicLong operations in Java 21 provide memory effects as specified by VarHandle operations
    // ensuring thread safety and visibility across both platform and virtual threads
    AtomicLong sequence = new AtomicLong(initialValue.getAsLong());
    
    // Return a lambda that atomically increments and returns the next value
    // incrementAndGet() has memory effects equivalent to VarHandle.getAndAdd() in Java 21
    return () -> sequence.incrementAndGet();
  }
}