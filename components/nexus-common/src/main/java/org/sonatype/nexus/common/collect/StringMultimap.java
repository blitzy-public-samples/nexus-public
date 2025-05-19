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

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * String multimap support with enhanced sequenced collection capabilities.
 * <p>
 * Provides methods to access and manipulate values in a defined encounter order,
 * including first/last element access and reverse traversal.
 *
 * @since 3.0
 */
public class StringMultimap
    implements Iterable<Entry<String, String>>
{
  private final ListMultimap<String, String> backing;

  public StringMultimap(final ListMultimap<String, String> entries) {
    this.backing = checkNotNull(entries);
  }

  public StringMultimap() {
    this(ArrayListMultimap.<String, String>create());
  }

  /**
   * Check if a named entry exists.
   */
  public boolean contains(final String name) {
    return backing.containsKey(name);
  }

  /**
   * Returns all values for named entry.
   */
  public List<String> getAll(final String name) {
    return backing.get(name);
  }

  /**
   * Returns all values for named entry in reverse order.
   */
  public List<String> getAllReversed(final String name) {
    List<String> values = backing.get(name);
    return values.reversed();
  }

  /**
   * Returns first value of named entry.
   */
  @Nullable
  public String get(final String name) {
    List<String> values = backing.get(name);
    if (!values.isEmpty()) {
      return values.getFirst();
    }
    return null;
  }

  /**
   * Returns first value of named entry.
   * <p>
   * Alias for {@link #get(String)} for consistency with {@link #getLast(String)}.
   */
  @Nullable
  public String getFirst(final String name) {
    return get(name);
  }

  /**
   * Returns last value of named entry.
   */
  @Nullable
  public String getLast(final String name) {
    List<String> values = backing.get(name);
    if (!values.isEmpty()) {
      return values.getLast();
    }
    return null;
  }

  /**
   * Set one or more named entry values.
   * <p>
   * Values are added to the end of the list.
   */
  public void set(final String name, final String... values) {
    for (String value : values) {
      backing.put(name, value);
    }
  }

  /**
   * Add a value as the first element for the named entry.
   */
  public void addFirst(final String name, final String value) {
    List<String> values = backing.get(name);
    values.addFirst(value);
  }

  /**
   * Add a value as the last element for the named entry.
   * <p>
   * Equivalent to {@link #set(String, String...)} with a single value.
   */
  public void addLast(final String name, final String value) {
    backing.put(name, value);
  }

  /**
   * Replace any existing values.
   */
  public void replace(final String name, final String... values) {
    backing.replaceValues(name, Arrays.asList(values));
  }

  /**
   * Set one or more named entry values.
   */
  public void set(final String name, final Iterable<String> values) {
    backing.putAll(name, values);
  }

  /**
   * Remove named entry values.
   */
  public void remove(final String name) {
    backing.removeAll(name);
  }

  /**
   * Remove and return the first value of named entry.
   *
   * @return the first value, or null if the entry doesn't exist or is empty
   */
  @Nullable
  public String removeFirst(final String name) {
    List<String> values = backing.get(name);
    if (!values.isEmpty()) {
      return values.removeFirst();
    }
    return null;
  }

  /**
   * Remove and return the last value of named entry.
   *
   * @return the last value, or null if the entry doesn't exist or is empty
   */
  @Nullable
  public String removeLast(final String name) {
    List<String> values = backing.get(name);
    if (!values.isEmpty()) {
      return values.removeLast();
    }
    return null;
  }

  public void clear() {
    backing.clear();
  }

  /**
   * Returns all entry names.
   */
  public Set<String> names() {
    return backing.keySet();
  }

  // FIXME: Sort out size() and entries() mismatch?

  /**
   * Returns number of named entries.
   */
  public int size() {
    return backing.keySet().size();
  }

  public boolean isEmpty() {
    return backing.isEmpty();
  }

  public Collection<Entry<String, String>> entries() {
    return backing.entries();
  }

  @Override
  public Iterator<Entry<String, String>> iterator() {
    return backing.entries().iterator();
  }

  /**
   * Returns a reversed view of this multimap's entries.
   * <p>
   * Changes to the original multimap are reflected in the reversed view.
   *
   * @return a reversed view of this multimap's entries
   */
  public Iterable<Entry<String, String>> reversed() {
    return backing.entries().reversed();
  }

  @Override
  public String toString() {
    return backing.toString();
  }

  // TODO: type coercion helpers
}