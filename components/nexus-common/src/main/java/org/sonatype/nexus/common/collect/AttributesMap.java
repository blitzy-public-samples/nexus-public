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

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SequencedSet;
import java.util.SequencedMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import com.google.common.reflect.TypeToken;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Container for [String -> Object] attributes.
 * 
 * When backed by a SequencedMap implementation, this class leverages the Java 21
 * Sequenced Collections API to provide ordered operations.
 *
 * @since 3.0
 */
public class AttributesMap
    implements Iterable<Entry<String, Object>>
{
  private static final Logger log = LoggerFactory.getLogger(AttributesMap.class);

  @JsonProperty
  protected final Map<String, Object> backing;

  public AttributesMap(final Map<String, Object> backing) {
    this.backing = checkNotNull(backing);
  }

  /**
   * Creates a new AttributesMap with a LinkedHashMap backing to leverage
   * the Sequenced Collections API for ordered operations.
   */
  public AttributesMap() {
    this(new LinkedHashMap<String, Object>());
  }

  /**
   * Expose the underlying backing for attributes.
   * 
   * @return The backing map, which may be a SequencedMap implementation
   */
  public Map<String, Object> backing() {
    return backing;
  }
  
  /**
   * Returns the backing map as a SequencedMap if it implements that interface.
   * 
   * @return The backing map as a SequencedMap, or null if it doesn't implement SequencedMap
   */
  @Nullable
  public SequencedMap<String, Object> sequencedBacking() {
    return backing instanceof SequencedMap ? (SequencedMap<String, Object>) backing : null;
  }

  /**
   * Coerce value to type.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  private <T> T coerce(final Object value, final TypeToken<T> type) {
    if (value != null) {
      // TODO: PropertyEditor coercion?
      log.trace("Coerce: {} -> {}", value, type);
      try {
        // Using Pattern Matching for switch to handle different value types
        Class<?> rawType = type.getRawType();
        return switch (value) {
          // Special handling for when Date has become a long (ms since epoch)
          case Number n when Date.class.equals(rawType) -> 
              (T) new Date(n.longValue());
          case Number n when DateTime.class.equals(rawType) -> 
              (T) new DateTime(n.longValue());
          case Number n when OffsetDateTime.class.equals(rawType) -> 
              (T) OffsetDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneOffset.UTC);
          
          // Special handling for booleans from string
          case String s when Boolean.class.equals(rawType) -> {
            T result = (T) Boolean.valueOf(s);
            Throwable stackThrow = null;
            if (log.isDebugEnabled()) {
              stackThrow = new Throwable("Unexpected Attribute Coercion. Stack trace output to locate area of concern:");
            }
            log.warn("Coerced Boolean Attribute from String. value={}, result={}", value, result, stackThrow);
            yield result;
          }
          
          // Default case - try to cast the value
          default -> (T) rawType.cast(value);
        };
      }
      catch (ClassCastException ex) {
        // Failure is unexpected but should at least be reported.
        log.warn("Invalid attempt to coerce Attribute into a {}. Original type {}. value={}",
            type.getType().getTypeName(),
            value.getClass().getName(),
            value,
            log.isDebugEnabled() ? ex : null);
        return null;
      }
    }
    return null;
  }

  /**
   * Get attribute value for given key.
   */
  @Nullable
  public Object get(final String key) {
    checkNotNull(key);
    return backing.get(key);
  }

  /**
   * Get attribute value for given key with default-value if value is null.
   */
  @Nullable
  public Object get(final String key, @Nullable final Object defaultValue) {
    Object value = get(key);
    if (value == null) {
      value = defaultValue;
    }
    return value;
  }

  /**
   * Get attribute value.
   */
  @Nullable
  public <T> T get(final Class<T> type) {
    Object value = get(AttributeKey.get(type));
    return type.cast(value);
  }

  /**
   * Get attribute value or create attribute value if not set.
   *
   * @return Existing or newly created attribute value.
   */
  public <T> T getOrCreate(final Class<T> type) {
    T value = get(type);
    if (value == null) {
      try {
        // create new attribute value with accessible=true ctor to allow construction of private/package-private
        Constructor<T> ctor = type.getDeclaredConstructor();
        log.trace("Creating '{}' with constructor: {}", type, ctor);
        ctor.setAccessible(true);
        value = ctor.newInstance();
      }
      catch (Exception e) {
        Throwables.throwIfUnchecked(e);
        throw new RuntimeException(e);
      }
      set(type, value);
    }
    return value;
  }

  /**
   * Get attribute value with type-key with default-value if value is null.
   */
  @Nullable
  public <T> T get(final Class<T> type, @Nullable final T defaultValue) {
    T value = get(type);
    if (value == null) {
      value = defaultValue;
    }
    return value;
  }

  /**
   * Get coerced attribute value for given key.
   */
  @Nullable
  public <T> T get(final String key, final TypeToken<T> type) {
    checkNotNull(type);
    Object value = get(key);
    return coerce(value, type);
  }

  /**
   * Get coerced attribute value for given key.
   */
  @Nullable
  public <T> T get(final String key, final Class<T> type) {
    checkNotNull(type);
    return get(key, TypeToken.of(type));
  }

  /**
   * Get coerced attribute value for given key with default-value if value is null.
   */
  @Nullable
  public <T> T get(final String key, final TypeToken<T> type, @Nullable final T defaultValue) {
    T value = get(key, type);
    if (value == null) {
      value = defaultValue;
    }
    return value;
  }

  /**
   * Get coerced attribute value for given key with default-value if value is null.
   */
  @Nullable
  public <T> T get(final String key, final Class<T> type, @Nullable final T defaultValue) {
    checkNotNull(type);
    return get(key, TypeToken.of(type), defaultValue);
  }

  /**
   * Allow customization of missing key message.
   */
  protected String missingKeyMessage(final String key) {
    return "Missing: " + key;
  }

  /**
   * Get required attribute value for given key.
   */
  public Object require(final String key) {
    Object value = get(key);
    checkState(value != null, missingKeyMessage(key));
    return value;
  }

  /**
   * Get required attribute value for given type-key.
   */
  public <T> T require(final Class<T> type) {
    T value = get(type);
    checkState(value != null, missingKeyMessage(AttributeKey.get(type)));
    return value;
  }

  /**
   * Require attribute value with coercion support.
   */
  public <T> T require(final String key, final TypeToken<T> type) {
    checkNotNull(type);
    Object value = require(key);
    return coerce(value, type);
  }

  /**
   * Require attribute value with coercion support.
   */
  public <T> T require(final String key, final Class<T> type) {
    checkNotNull(type);
    return require(key, TypeToken.of(type));
  }

  /**
   * Set keyed attribute value.
   */
  @Nullable
  public Object set(final String key, @Nullable final Object value) {
    checkNotNull(key);
    if (value == null) {
      return remove(key);
    }
    Object replaced = backing.put(key, value);
    if (log.isTraceEnabled()) {
      log.trace("Set: {}={} ({})", key, value, value.getClass().getName());
    }
    return replaced;
  }

  /**
   * Compute new value for keyed attribute based on current value.
   */
  @Nullable
  public Object compute(final String key, final Function<Object, ? extends Object> function) {
    return set(key, function.apply(get(key)));
  }

  /**
   * Set type-keyed attribute value.
   */
  @Nullable
  public <T> Object set(final Class<T> type, @Nullable final T value) {
    return set(AttributeKey.get(type), value);
  }

  /**
   * Remove attribute for given key.
   */
  @Nullable
  public Object remove(final String key) {
    checkNotNull(key);
    Object removed = backing.remove(key);
    if (removed != null) {
      log.trace("Removed: {}", key);
    }
    return removed;
  }

  /**
   * Remove attribute for given type-key.
   */
  @Nullable
  public Object remove(final Class<?> type) {
    return remove(AttributeKey.get(type));
  }

  /**
   * Check if attributes contains given key.
   */
  public boolean contains(final String key) {
    checkNotNull(key);
    return backing.containsKey(key);
  }

  /**
   * Check if attributes contains given type-key.
   */
  public boolean contains(final Class<?> type) {
    return contains(AttributeKey.get(type));
  }

  /**
   * Return all attribute keys.
   * 
   * @return A SequencedSet if the backing map is a SequencedMap, otherwise a regular Set
   */
  public Set<String> keys() {
    if (backing instanceof SequencedMap<String, Object> sequencedMap) {
      return sequencedMap.sequencedKeySet();
    }
    return backing.keySet();
  }

  /**
   * Return all attribute entries.
   * 
   * @return A SequencedSet if the backing map is a SequencedMap, otherwise a regular Set
   */
  public Set<Entry<String, Object>> entries() {
    if (backing instanceof SequencedMap<String, Object> sequencedMap) {
      return sequencedMap.sequencedEntrySet();
    }
    return backing.entrySet();
  }

  /**
   * Returns an iterator over the entries in this map.
   * If the backing map is a SequencedMap, the iterator will respect the encounter order.
   */
  @Override
  public Iterator<Entry<String, Object>> iterator() {
    if (backing instanceof SequencedMap<String, Object> sequencedMap) {
      return sequencedMap.sequencedEntrySet().iterator();
    }
    return backing.entrySet().iterator();
  }

  /**
   * Check if attributes contains any values.
   */
  @JsonIgnore
  public boolean isEmpty() {
    return backing.isEmpty();
  }

  /**
   * Returns the number of defined attributes.
   */
  public int size() {
    return backing.size();
  }

  /**
   * Clear all attributes.
   */
  public void clear() {
    backing.clear();
    log.trace("Cleared");
  }

  @Override
  public String toString() {
    return backing.toString();
  }
}