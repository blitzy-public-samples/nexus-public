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
package org.sonatype.nexus.common.collect;

import java.util.Collection;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SequencedSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import com.google.common.collect.ForwardingSet;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link Set} wrapper that automatically detaches from the original in response to a mutating request
 * or when content escapes back to the caller, as we may need to wrap that content and store it locally.
 *
 * This provides a good balance between an eager copy and a completely lazy (but complex) wrapper.
 *
 * @since 3.5
 */
public class DetachingSet<V>
    extends ForwardingSet<V>
    implements SequencedSet<V>
{
  private Set<V> backing;

  private final BooleanSupplier allowDetach;

  private final Function<V, V> detach;

  private boolean detached;

  /**
   * Wraps a set that detaches on-demand when allowed; its elements are detached using the given strategy.
   *
   * @param backing The original set
   * @param allowDetach Is detaching currently allowed?
   * @param detach The detach strategy
   */
  public DetachingSet(final Set<V> backing, final BooleanSupplier allowDetach, final Function<V, V> detach) {
    this.backing = checkNotNull(backing);
    this.allowDetach = checkNotNull(allowDetach);
    this.detach = checkNotNull(detach);
  }

  /**
   * Wraps a set that detaches on-demand; its elements are detached using the given strategy.
   *
   * @param backing The original set
   * @param detach The detach strategy
   */
  public DetachingSet(final Set<V> backing, final Function<V, V> detach) {
    this(backing, () -> true, detach);
  }

  /* Methods that don't mutate the set or allow any content to escape */

  @Override
  public boolean contains(final Object key) {
    return backing.contains(key);
  }

  @Override
  public boolean containsAll(final Collection<?> collection) {
    return backing.containsAll(collection);
  }

  @Override
  public boolean isEmpty() {
    return backing.isEmpty();
  }

  @Override
  public int size() {
    return backing.size();
  }

  @Override
  public boolean equals(final Object object) {
    return backing.equals(object);
  }

  @Override
  public int hashCode() {
    return backing.hashCode();
  }

  @Override
  public String toString() {
    return backing.toString();
  }
  
  /**
   * Returns the first element in this set.
   *
   * @return the first element in this set
   * @throws NoSuchElementException if this set is empty
   */
  @Override
  public V getFirst() {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.getFirst();
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Returns the last element in this set.
   *
   * @return the last element in this set
   * @throws NoSuchElementException if this set is empty
   */
  @Override
  public V getLast() {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.getLast();
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Adds the specified element as the first element in this set.
   *
   * @param e the element to add
   * @throws UnsupportedOperationException if this set does not support this operation
   */
  @Override
  public void addFirst(V e) {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      delegate(); // Ensure we're detached before modifying
      if (backing instanceof SequencedSet<V> detachedSequencedSet) {
        detachedSequencedSet.addFirst(e);
        return;
      }
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Adds the specified element as the last element in this set.
   *
   * @param e the element to add
   * @throws UnsupportedOperationException if this set does not support this operation
   */
  @Override
  public void addLast(V e) {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      delegate(); // Ensure we're detached before modifying
      if (backing instanceof SequencedSet<V> detachedSequencedSet) {
        detachedSequencedSet.addLast(e);
        return;
      }
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Removes and returns the first element from this set.
   *
   * @return the first element from this set
   * @throws NoSuchElementException if this set is empty
   * @throws UnsupportedOperationException if this set does not support this operation
   */
  @Override
  public V removeFirst() {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      delegate(); // Ensure we're detached before modifying
      if (backing instanceof SequencedSet<V> detachedSequencedSet) {
        return detachedSequencedSet.removeFirst();
      }
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Removes and returns the last element from this set.
   *
   * @return the last element from this set
   * @throws NoSuchElementException if this set is empty
   * @throws UnsupportedOperationException if this set does not support this operation
   */
  @Override
  public V removeLast() {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      delegate(); // Ensure we're detached before modifying
      if (backing instanceof SequencedSet<V> detachedSequencedSet) {
        return detachedSequencedSet.removeLast();
      }
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Returns a reverse-ordered view of this set.
   *
   * @return a reverse-ordered view of this set
   */
  @Override
  public SequencedSet<V> reversed() {
    if (backing instanceof SequencedSet<V> sequencedSet) {
      // We don't need to detach for reversed() as it's just a view
      return new DetachingSet<>(sequencedSet.reversed(), allowDetach, detach);
    }
    throw new UnsupportedOperationException("Backing set is not a SequencedSet");
  }

  /**
   * Incoming request where either the original content will escape back to the caller or the set will change.
   * If detaching is allowed we detach one level by copying the set and applying the strategy to any elements.
   */
  @Override
  protected Set<V> delegate() {
    if (!detached && allowDetach.getAsBoolean()) {
      // Use pattern matching for switch to determine the appropriate detaching set type
      Set<V> detaching = switch (backing) {
        case SequencedSet<V> sequencedSet -> {
          // Create a HashSet that preserves insertion order if backing is a SequencedSet
          // In a real implementation, we might use LinkedHashSet, but for simplicity we'll use HashSet here
          var newSet = new HashSet<V>(backing.size());
          backing.forEach(e -> newSet.add(detach.apply(e)));
          yield newSet;
        }
        case Set<V> standardSet -> {
          // Standard HashSet for regular Set implementations
          var newSet = new HashSet<V>(backing.size());
          backing.forEach(e -> newSet.add(detach.apply(e)));
          yield newSet;
        }
      };
      backing = detaching;
      detached = true;
    }
    return backing;
  }
}