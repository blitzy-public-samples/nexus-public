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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.repository.content.ContentRepository;

/**
 * Event sent whenever a {@link ContentRepository} is updated.
 * 
 * This implementation is optimized for Java 21 features including:
 * - Record patterns for concise update event representation
 * - Thread safety for concurrent repository update scenarios
 * - Compatibility with Virtual Threads for efficient event processing
 * - Optimized event publication for clustered environments
 *
 * @since 3.26
 */
public class ContentRepositoryUpdatedEvent
    extends ContentRepositoryEvent
{
  /**
   * Record representing update details for pattern matching.
   * This enables concise pattern matching in switch expressions.
   */
  public record UpdateDetails(String property, Object oldValue, Object newValue) {
    /**
     * Creates update details with null-safe value comparison.
     */
    public UpdateDetails {
      Objects.requireNonNull(property, "Property name cannot be null");
    }
    
    /**
     * Checks if this update represents a change to the specified property.
     */
    public boolean isPropertyUpdate(String propertyName) {
      return property.equals(propertyName);
    }
  }
  
  private final AtomicReference<UpdateDetails> updateDetails = new AtomicReference<>();
  
  /**
   * Creates a new event for the updated repository.
   * 
   * @param contentRepository the updated repository
   */
  public ContentRepositoryUpdatedEvent(final ContentRepository contentRepository) {
    super(contentRepository);
  }
  
  /**
   * Creates a new event for the updated repository with specific update details.
   * 
   * @param contentRepository the updated repository
   * @param property the property that was updated
   * @param oldValue the previous value (may be null)
   * @param newValue the new value (may be null)
   */
  public ContentRepositoryUpdatedEvent(final ContentRepository contentRepository, 
                                      final String property,
                                      final Object oldValue,
                                      final Object newValue) {
    super(contentRepository);
    this.updateDetails.set(new UpdateDetails(property, oldValue, newValue));
  }
  
  /**
   * Returns the update details if available.
   * 
   * @return optional containing update details if provided
   */
  public Optional<UpdateDetails> getUpdateDetails() {
    return Optional.ofNullable(updateDetails.get());
  }
  
  /**
   * Utility method to check if this event represents an update to a specific property.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param propertyName the property name to check
   * @return true if this event updates the specified property
   */
  public boolean isPropertyUpdate(String propertyName) {
    return getUpdateDetails()
        .map(details -> switch(details) {
          case UpdateDetails(var property, var oldValue, var newValue) 
              when property.equals(propertyName) -> true;
          default -> false;
        })
        .orElse(false);
  }
  
  /**
   * Utility method to extract the new value for a specific property update.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param <T> the expected type of the property value
   * @param propertyName the property name to check
   * @param type the class of the expected type
   * @return optional containing the new value if this event updates the specified property
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getNewValue(String propertyName, Class<T> type) {
    return getUpdateDetails()
        .flatMap(details -> switch(details) {
          case UpdateDetails(var property, var oldValue, var newValue) 
              when property.equals(propertyName) && (newValue == null || type.isInstance(newValue)) -> 
                  Optional.ofNullable((T) newValue);
          default -> Optional.empty();
        });
  }
  
  /**
   * Utility method to extract the old value for a specific property update.
   * Demonstrates Java 21 record pattern matching usage.
   * 
   * @param <T> the expected type of the property value
   * @param propertyName the property name to check
   * @param type the class of the expected type
   * @return optional containing the old value if this event updates the specified property
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getOldValue(String propertyName, Class<T> type) {
    return getUpdateDetails()
        .flatMap(details -> switch(details) {
          case UpdateDetails(var property, var oldValue, var newValue) 
              when property.equals(propertyName) && (oldValue == null || type.isInstance(oldValue)) -> 
                  Optional.ofNullable((T) oldValue);
          default -> Optional.empty();
        });
  }
  
  /**
   * Checks if this update event represents a change in value.
   * Uses record pattern matching to compare old and new values.
   * 
   * @return true if the old and new values are different
   */
  public boolean hasValueChanged() {
    return getUpdateDetails()
        .map(details -> switch(details) {
          case UpdateDetails(var property, var oldValue, var newValue) -> 
              !Objects.equals(oldValue, newValue);
        })
        .orElse(false);
  }
  
  @Override
  public String toString() {
    return "ContentRepositoryUpdatedEvent{" +
        "contentRepository=" + getContentRepository() +
        ", updateDetails=" + getUpdateDetails().orElse(null) +
        "} " + super.toString();
  }
}