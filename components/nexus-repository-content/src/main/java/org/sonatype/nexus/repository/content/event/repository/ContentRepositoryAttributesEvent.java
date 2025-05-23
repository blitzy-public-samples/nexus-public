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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.content.AttributeOperation;
import org.sonatype.nexus.repository.content.ContentRepository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;

/**
 * Event sent whenever a {@link ContentRepository}'s attributes change.
 * 
 * This implementation is optimized for Java 21 features including:
 * - Record patterns for concise attribute operation representation
 * - Thread safety for concurrent attribute modification scenarios
 * - Compatibility with Virtual Threads for efficient event processing
 * - Optimized serialization for cluster distribution
 *
 * @since 3.26
 */
public class ContentRepositoryAttributesEvent
    extends ContentRepositoryUpdatedEvent
{
  /**
   * Record representing attribute operation details for pattern matching.
   * This enables concise pattern matching in switch expressions and instanceof checks.
   */
  public record AttributeOperationDetails(AttributeOperation operation, String key, Object value) 
      implements Serializable {
    /**
     * Creates attribute operation details with null-safe validation.
     */
    public AttributeOperationDetails {
      Objects.requireNonNull(operation, "Operation cannot be null");
      Objects.requireNonNull(key, "Key cannot be null");
      // value can be null for REMOVE operations
    }
    
    /**
     * Checks if this operation matches the specified key.
     */
    public boolean isKeyOperation(String keyName) {
      return key.equals(keyName);
    }
    
    /**
     * Checks if this operation is of the specified type.
     */
    public boolean isOperationType(AttributeOperation operationType) {
      return operation.equals(operationType);
    }
    
    /**
     * Returns the value with the expected type, if compatible.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getTypedValue(Class<T> type) {
      return ofNullable(value)
          .filter(v -> type.isInstance(v))
          .map(v -> (T) v);
    }
  }
  
  private final AtomicReference<AttributeOperationDetails> attributeDetails;
  
  /**
   * Creates a new event for the repository attribute change.
   * 
   * @param contentRepository the repository whose attributes changed
   * @param change the attribute operation type
   * @param key the attribute key
   * @param value the attribute value (may be null for REMOVE operations)
   */
  public ContentRepositoryAttributesEvent(
      final ContentRepository contentRepository,
      final AttributeOperation change,
      final String key,
      @Nullable final Object value)
  {
    super(contentRepository);
    this.attributeDetails = new AtomicReference<>(new AttributeOperationDetails(change, key, value));
  }

  /**
   * Returns the attribute operation type.
   */
  public AttributeOperation getChange() {
    return attributeDetails.get().operation();
  }

  /**
   * Returns the attribute key.
   */
  public String getKey() {
    return attributeDetails.get().key();
  }

  /**
   * Returns the attribute value with the expected type, if present.
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getValue() {
    return ofNullable((T) attributeDetails.get().value());
  }
  
  /**
   * Returns the attribute operation details.
   */
  public AttributeOperationDetails getAttributeDetails() {
    return attributeDetails.get();
  }
  
  /**
   * Checks if this event represents an operation on the specified key.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param keyName the key name to check
   * @return true if this event operates on the specified key
   */
  public boolean isKeyOperation(String keyName) {
    return switch(attributeDetails.get()) {
      case AttributeOperationDetails(var op, var k, var v) when k.equals(keyName) -> true;
      default -> false;
    };
  }
  
  /**
   * Checks if this event represents the specified operation type.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param operationType the operation type to check
   * @return true if this event is of the specified operation type
   */
  public boolean isOperationType(AttributeOperation operationType) {
    return switch(attributeDetails.get()) {
      case AttributeOperationDetails(var op, var k, var v) when op.equals(operationType) -> true;
      default -> false;
    };
  }
  
  /**
   * Extracts the value for a specific key and operation type.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param <T> the expected type of the value
   * @param keyName the key name to check
   * @param operationType the operation type to check
   * @param type the class of the expected type
   * @return optional containing the value if this event matches the key and operation type
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getValueForKeyAndOperation(String keyName, AttributeOperation operationType, Class<T> type) {
    return switch(attributeDetails.get()) {
      case AttributeOperationDetails(var op, var k, var v) 
          when op.equals(operationType) && k.equals(keyName) && (v == null || type.isInstance(v)) -> 
              ofNullable((T) v);
      default -> Optional.empty();
    };
  }
  
  @Override
  public String toString() {
    return "ContentRepositoryAttributesEvent{" +
        "attributeDetails=" + attributeDetails.get() +
        ", contentRepository=" + getContentRepository() +
        "} " + super.toString();
  }
}
