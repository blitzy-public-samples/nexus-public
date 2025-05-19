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
package org.sonatype.nexus.repository.content.fluent;

import java.util.Collection;
import java.util.SequencedCollection;
import java.util.NoSuchElementException;

import org.sonatype.nexus.common.entity.Continuation;

import com.google.common.base.Function;
import com.google.common.collect.ForwardingCollection;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Collections2.transform;

/**
 * Fluent {@link Continuation}s that implement {@link SequencedCollection} for Java 21.
 *
 * @since 3.24
 */
public class FluentContinuation<E, T>
    extends ForwardingCollection<E>
    implements Continuation<E>, SequencedCollection<E>
{
  private final Continuation<T> continuation;

  private final Collection<E> fluentCollection;

  /**
   * Creates a new fluent continuation.
   *
   * @param continuation the underlying continuation
   * @param toFluent function to transform elements from T to E
   */
  public FluentContinuation(final Continuation<T> continuation, final Function<T, E> toFluent) {
    this.continuation = checkNotNull(continuation);
    // Using Java 21's more concise syntax for transforming collections
    this.fluentCollection = transform(continuation, toFluent);
  }

  @Override
  protected Collection<E> delegate() {
    return fluentCollection;
  }

  @Override
  public String nextContinuationToken() {
    return continuation.nextContinuationToken();
  }

  // SequencedCollection implementation

  @Override
  public SequencedCollection<E> reversed() {
    throw new UnsupportedOperationException("Reversed view not supported for FluentContinuation");
  }

  @Override
  public void addFirst(E e) {
    throw new UnsupportedOperationException("Adding elements not supported for FluentContinuation");
  }

  @Override
  public void addLast(E e) {
    throw new UnsupportedOperationException("Adding elements not supported for FluentContinuation");
  }

  @Override
  public E getFirst() {
    if (isEmpty()) {
      throw new NoSuchElementException("FluentContinuation is empty");
    }
    return iterator().next();
  }

  @Override
  public E getLast() {
    if (isEmpty()) {
      throw new NoSuchElementException("FluentContinuation is empty");
    }
    // Since we don't have direct access to the last element without iterating through the entire collection,
    // this operation is inefficient but necessary for the SequencedCollection contract
    E last = null;
    for (E element : this) {
      last = element;
    }
    return last;
  }

  @Override
  public E removeFirst() {
    throw new UnsupportedOperationException("Removing elements not supported for FluentContinuation");
  }

  @Override
  public E removeLast() {
    throw new UnsupportedOperationException("Removing elements not supported for FluentContinuation");
  }
}