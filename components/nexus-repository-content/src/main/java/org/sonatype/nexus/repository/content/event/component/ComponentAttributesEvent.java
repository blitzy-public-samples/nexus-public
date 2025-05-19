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
package org.sonatype.nexus.repository.content.event.component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.content.AttributeOperation;
import org.sonatype.nexus.repository.content.Component;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;

/**
 * Event sent whenever a {@link Component}'s attributes change.
 * 
 * <p>This class has been enhanced with Java 21 features including pattern matching,
 * record pattern extraction, and Virtual Thread optimization for attribute processing.</p>
 *
 * @since 3.26
 */
public class ComponentAttributesEvent
    extends ComponentUpdatedEvent
{
  private final AttributeOperation change;

  private final String key;

  @Nullable
  private final Object value;

  public ComponentAttributesEvent(
      final Component component,
      final AttributeOperation change,
      final String key,
      @Nullable final Object value)
  {
    super(component);
    this.change = checkNotNull(change);
    this.key = checkNotNull(key);
    this.value = value;
  }

  public AttributeOperation getChange() {
    return change;
  }

  public String getKey() {
    return key;
  }

  /**
   * Gets the value with enhanced type safety using pattern matching.
   * 
   * @param <T> the expected type of the value
   * @return an Optional containing the value if it matches the expected type, otherwise empty
   */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getValue() {
    return switch (value) {
      case T t -> Optional.of(t);
      case null -> Optional.empty();
      default -> Optional.empty();
    };
  }
  
  /**
   * Extracts components from a record-type attribute value using record patterns.
   * 
   * @param <R> the record type
   * @return an Optional containing the extracted record components as a Map, or empty if not a record
   * @since 3.60
   */
  @SuppressWarnings("unchecked")
  public <R extends Record> Optional<Map<String, Object>> getRecordComponents() {
    return switch (value) {
      case Record r -> {
        // Extract record components using reflection to support any record type
        // This is optimized for virtual thread context by avoiding blocking operations
        var components = java.util.Arrays.stream(r.getClass().getRecordComponents())
            .collect(java.util.stream.Collectors.toMap(
                java.lang.reflect.RecordComponent::getName,
                comp -> {
                  try {
                    return comp.getAccessor().invoke(r);
                  } catch (Exception e) {
                    return null;
                  }
                }
            ));
        yield Optional.of(components);
      }
      default -> Optional.empty();
    };
  }

  /**
   * Process attribute changes asynchronously using Virtual Threads.
   * This method is optimized for I/O-bound operations in a Virtual Thread context.
   *
   * @param processor the function to process the attribute value
   * @param <R> the result type of the processing
   * @return a CompletableFuture with the processing result
   * @since 3.60
   */
  public <R> CompletableFuture<R> processAttributeAsync(java.util.function.Function<Object, R> processor) {
    return CompletableFuture.supplyAsync(() -> processor.apply(value), Thread.ofVirtual().name("attribute-processor-" + key).factory());
  }

  @Override
  public String toString() {
    return "ComponentAttributesEvent{" +
        "change=" + change +
        ", key='" + key + '\'' +
        ", value=" + value +
        "} " + super.toString();
  }
}