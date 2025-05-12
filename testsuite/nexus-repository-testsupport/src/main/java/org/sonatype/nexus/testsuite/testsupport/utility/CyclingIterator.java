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
package org.sonatype.nexus.testsuite.testsupport.utility;

import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;

/**
 * Iterates through a List cyclically, without throwing ConcurrentModificationException.
 * 
 * <p>This iterator will continuously cycle through the provided list as long as it's not empty.
 * The {@link #hasNext()} method will return true indefinitely if the list is not empty,
 * and the {@link #next()} method will cycle back to the beginning of the list after reaching the end.</p>
 * 
 * <p>Note: Since this is a cycling iterator, the {@link #forEachRemaining(Consumer)} method
 * will process elements indefinitely if the list is not empty. Use with caution.</p>
 *
 * @param <T> the type of elements returned by this iterator
 */
public class CyclingIterator<T>
    implements Iterator<T>
{
  private final List<T> list;

  private int index = 0;

  /**
   * Constructs a new cycling iterator over the specified list.
   *
   * @param list the list to iterate over cyclically
   */
  public CyclingIterator(final List<T> list) {
    this.list = list;
  }

  @Override
  public boolean hasNext() {
    return !list.isEmpty();
  }

  @Override
  public synchronized T next() {
    checkState(!list.isEmpty(), "Cannot get next element from an empty list");
    if (index >= list.size()) {
      index = 0;
    }
    return list.get(index++);
  }

  /**
   * This implementation does nothing as removing elements is not supported.
   * 
   * <p>The cycling iterator is designed to iterate over a fixed list without modifying it.</p>
   */
  @Override
  public void remove() {
    // no-op
  }

  /**
   * Performs the given action on each element of the list, cycling indefinitely.
   * 
   * <p>Warning: This method will run indefinitely if the list is not empty, as this
   * is a cycling iterator that never terminates. It is recommended to limit the number
   * of iterations externally or avoid using this method.</p>
   *
   * @param action the action to be performed on each element
   * @throws NullPointerException if the specified action is null
   */
  @Override
  public void forEachRemaining(Consumer<? super T> action) {
    // Use the default implementation which will run indefinitely if the list is not empty
    // This is consistent with the cycling nature of this iterator
    super.forEachRemaining(action);
  }
}