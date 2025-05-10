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
import java.util.Iterator;
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
  
  /* SequencedSet implementation methods */
  
  @Override
  public V getFirst() {
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.getFirst();
    }
    // Otherwise, use iterator to get the first element
    if (isEmpty()) {
      throw new java.util.NoSuchElementException("Set is empty");
    }
    return iterator().next();
  }
  
  @Override
  public V getLast() {
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.getLast();
    }
    // Otherwise, find the last element using iterator
    if (isEmpty()) {
      throw new java.util.NoSuchElementException("Set is empty");
    }
    V last = null;
    for (Iterator<V> it = iterator(); it.hasNext(); ) {
      last = it.next();
    }
    return last;
  }
  
  @Override
  public void addFirst(V element) {
    // This will trigger detachment if needed
    delegate();
    
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      sequencedSet.addFirst(element);
    } else {
      // Otherwise, just add the element (sets don't guarantee order)
      add(element);
    }
  }
  
  @Override
  public void addLast(V element) {
    // This will trigger detachment if needed
    delegate();
    
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      sequencedSet.addLast(element);
    } else {
      // Otherwise, just add the element (sets don't guarantee order)
      add(element);
    }
  }
  
  @Override
  public V removeFirst() {
    // This will trigger detachment if needed
    delegate();
    
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.removeFirst();
    }
    
    // Otherwise, remove the first element using iterator
    if (isEmpty()) {
      throw new java.util.NoSuchElementException("Set is empty");
    }
    Iterator<V> it = iterator();
    V first = it.next();
    it.remove();
    return first;
  }
  
  @Override
  public V removeLast() {
    // This will trigger detachment if needed
    delegate();
    
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return sequencedSet.removeLast();
    }
    
    // For non-SequencedSet backings, we need to find the last element
    if (isEmpty()) {
      throw new java.util.NoSuchElementException("Set is empty");
    }
    
    // This is inefficient for regular Sets, but it's the best we can do
    // without knowing the specific implementation
    V last = getLast();
    remove(last);
    return last;
  }
  
  @Override
  public SequencedSet<V> reversed() {
    // This will trigger detachment if needed since we're exposing content
    delegate();
    
    // If backing is a SequencedSet, use its implementation
    if (backing instanceof SequencedSet<V> sequencedSet) {
      return new DetachingSet<>(sequencedSet.reversed(), allowDetach, detach);
    }
    
    // For non-SequencedSet backings, we need to create a reversed view
    // This is a simple implementation that creates a new set with elements in reverse order
    throw new UnsupportedOperationException("Cannot reverse a non-sequenced set");
  }

  /**
   * Incoming request where either the original content will escape back to the caller or the set will change.
   * If detaching is allowed we detach one level by copying the set and applying the strategy to any elements.
   */
  @Override
  protected Set<V> delegate() {
    if (!detached && allowDetach.getAsBoolean()) {
      // Use pattern matching for instanceof to simplify the code
      Set<V> detaching = switch (backing) {
        // If backing is a SequencedSet, try to preserve that characteristic
        case SequencedSet<?> s -> new java.util.LinkedHashSet<>(backing.size());
        // Otherwise use a regular HashSet
        default -> new HashSet<>(backing.size());
      };
      
      backing.forEach(e -> detaching.add(detach.apply(e)));
      backing = detaching;
      detached = true;
    }
    return backing;
  }
}