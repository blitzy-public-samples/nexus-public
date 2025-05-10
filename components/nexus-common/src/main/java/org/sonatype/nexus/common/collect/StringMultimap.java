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
import java.util.SequencedCollection;

import javax.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * String multimap support.
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
   * Returns first value of named entry.
   */
  @Nullable
  public String get(final String name) {
    List<String> values = backing.get(name);
    if (!values.isEmpty()) {
      return values.get(0);
    }
    return null;
  }

  /**
   * Returns first value of named entry.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  @Nullable
  public String getFirst(final String name) {
    List<String> values = backing.get(name);
    return values.isEmpty() ? null : values.getFirst();
  }

  /**
   * Returns last value of named entry.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  @Nullable
  public String getLast(final String name) {
    List<String> values = backing.get(name);
    return values.isEmpty() ? null : values.getLast();
  }

  /**
   * Set one or more named entry values.
   */
  public void set(final String name, final String... values) {
    for (String value : values) {
      backing.put(name, value);
    }
  }

  /**
   * Replace any existing values.
   */
  public void replace(final String name, final String... values) {
    backing.replaceValues(name, Arrays.asList(values));
  }

  /**
   * Set on or more named entry values.
   */
  public void set(final String name, final Iterable<String> values) {
    backing.putAll(name, values);
  }

  /**
   * Add a value as the first entry for the given name.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  public void addFirst(final String name, final String value) {
    List<String> values = backing.get(name);
    values.addFirst(value);
  }

  /**
   * Add a value as the last entry for the given name.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  public void addLast(final String name, final String value) {
    backing.put(name, value); // Same as regular put since ListMultimap appends to the end
  }

  /**
   * Remove named entry values.
   */
  public void remove(final String name) {
    backing.removeAll(name);
  }

  /**
   * Remove and return the first value of named entry.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  @Nullable
  public String removeFirst(final String name) {
    List<String> values = backing.get(name);
    if (values.isEmpty()) {
      return null;
    }
    String first = values.removeFirst();
    if (values.isEmpty()) {
      backing.removeAll(name); // Clean up the key if no values remain
    }
    return first;
  }

  /**
   * Remove and return the last value of named entry.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  @Nullable
  public String removeLast(final String name) {
    List<String> values = backing.get(name);
    if (values.isEmpty()) {
      return null;
    }
    String last = values.removeLast();
    if (values.isEmpty()) {
      backing.removeAll(name); // Clean up the key if no values remain
    }
    return last;
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

  /**
   * Returns a reversed view of the values for the named entry.
   * Leverages Java 21 Sequenced Collections API concept.
   */
  public SequencedCollection<String> reversed(final String name) {
    return backing.get(name).reversed();
  }

  @Override
  public Iterator<Entry<String, String>> iterator() {
    return backing.entries().iterator();
  }

  @Override
  public String toString() {
    return backing.toString();
  }

  // TODO: type coercion helpers
}