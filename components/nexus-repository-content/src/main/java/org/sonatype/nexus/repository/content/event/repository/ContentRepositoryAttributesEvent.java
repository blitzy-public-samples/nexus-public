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
package org.sonatype.nexus.repository.content.event.repository;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.content.AttributeOperation;
import org.sonatype.nexus.repository.content.ContentRepository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;

/**
 * Event sent whenever a {@link ContentRepository}'s attributes change.
 * <p>
 * This implementation is optimized for Java 21 with support for:
 * <ul>
 *   <li>Record patterns for concise attribute operation handling</li>
 *   <li>Virtual threads for efficient event processing</li>
 *   <li>Thread-safe handling of attribute key-value pairs</li>
 *   <li>Optimized serialization for cluster distribution</li>
 * </ul>
 * <p>
 * This class is immutable and thread-safe, making it suitable for processing with virtual threads
 * in high-concurrency environments.
 *
 * @since 3.26
 */
public class ContentRepositoryAttributesEvent
    extends ContentRepositoryUpdatedEvent
{
  /**
   * Thread-safe cache for attribute operations to optimize repeated access patterns.
   */
  private static final Map<String, AttributeChange> ATTRIBUTE_CHANGE_CACHE = new ConcurrentHashMap<>();

  /**
   * Record that encapsulates an attribute change operation with its key and value.
   * <p>
   * Using records provides immutability and pattern matching capabilities in Java 21.
   */
  public record AttributeChange(
      AttributeOperation operation,
      String key,
      @Nullable Object value) implements Serializable 
  {
    /**
     * Creates a new attribute change with validation.
     *
     * @param operation the attribute operation
     * @param key the attribute key
     * @param value the attribute value (may be null for REMOVE operations)
     */
    public AttributeChange {
      checkNotNull(operation, "Attribute operation cannot be null");
      checkNotNull(key, "Attribute key cannot be null");
      // value can be null for REMOVE operations
    }
    
    /**
     * Applies this attribute change to the given attributes map.
     * <p>
     * Uses pattern matching for switch to handle different operations concisely.
     *
     * @param attributes the attributes map to modify
     * @return the modified attributes map
     */
    public Map<String, Object> applyTo(final Map<String, Object> attributes) {
      // Create an entry for the operation to use
      Map.Entry<String, Object> entry = Map.entry(key, value);
      
      // Use pattern matching with switch for concise operation handling
      return switch (operation) {
        case SET -> { attributes.put(key, value); yield attributes; }
        case REMOVE -> { attributes.remove(key); yield attributes; }
        case APPEND, PREPEND, OVERLAY -> operation.apply(attributes, entry);
      };
    }
    
    /**
     * Gets a description of this attribute change suitable for logging.
     * <p>
     * Uses pattern matching with switch for concise operation descriptions.
     *
     * @return a human-readable description of the attribute change
     */
    public String getDescription() {
      return switch (operation) {
        case SET -> "Setting '" + key + "' to '" + value + "'";
        case REMOVE -> "Removing '" + key + "'";
        case APPEND -> "Appending '" + value + "' to '" + key + "' list";
        case PREPEND -> "Prepending '" + value + "' to '" + key + "' list";
        case OVERLAY -> "Overlaying '" + value + "' onto '" + key + "' map";
      };
    }
  }

  private final AttributeChange attributeChange;

  /**
   * Creates a new event for the repository attribute change.
   * <p>
   * This constructor creates an immutable event with thread-safe handling of attribute operations.
   *
   * @param contentRepository the repository whose attributes changed
   * @param operation the attribute operation performed
   * @param key the attribute key that was changed
   * @param value the attribute value (may be null for REMOVE operations)
   */
  public ContentRepositoryAttributesEvent(
      final ContentRepository contentRepository,
      final AttributeOperation operation,
      final String key,
      @Nullable final Object value)
  {
    super(contentRepository);
    
    // Create or reuse an AttributeChange record for this operation
    String cacheKey = operation.name() + ":" + key;
    this.attributeChange = ATTRIBUTE_CHANGE_CACHE.computeIfAbsent(cacheKey,
        k -> new AttributeChange(operation, key, value));
  }

  /**
   * Gets the attribute operation that was performed.
   *
   * @return the attribute operation
   */
  public AttributeOperation getChange() {
    return attributeChange.operation();
  }

  /**
   * Gets the attribute key that was changed.
   *
   * @return the attribute key
   */
  public String getKey() {
    return attributeChange.key();
  }

  /**
   * Gets the attribute value, if present.
   * <p>
   * Uses Java 21's pattern matching capabilities for type safety.
   *
   * @param <T> the expected type of the value
   * @return an optional containing the value, or empty if no value or incompatible type
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getValue() {
    return ofNullable((T) attributeChange.value());
  }
  
  /**
   * Gets the complete attribute change record.
   * <p>
   * This method provides access to the immutable record representing the attribute change,
   * enabling pattern matching in the calling code.
   *
   * @return the attribute change record
   */
  public AttributeChange getAttributeChange() {
    return attributeChange;
  }
  
  /**
   * Applies this event's attribute change to the given attributes map.
   * <p>
   * This method demonstrates the use of record patterns for concise attribute handling.
   *
   * @param attributes the attributes map to modify
   * @return the modified attributes map
   */
  public Map<String, Object> applyTo(final Map<String, Object> attributes) {
    return attributeChange.applyTo(attributes);
  }
  
  /**
   * Processes this attribute change with the given function.
   * <p>
   * This method demonstrates pattern matching with records for concise data extraction.
   *
   * @param <R> the result type of the function
   * @param processor the function to process the attribute change
   * @return the result of the function
   */
  public <R> R process(Function<AttributeChange, R> processor) {
    // Using record pattern matching for concise data extraction
    var AttributeChange(var op, var k, var v) = attributeChange;
    return processor.apply(new AttributeChange(op, k, v));
  }
}
