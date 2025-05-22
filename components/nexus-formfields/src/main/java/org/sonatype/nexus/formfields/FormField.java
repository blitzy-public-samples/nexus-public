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
package org.sonatype.nexus.formfields;

import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

/**
 * Form field interface defining the contract for form field implementations.
 * 
 * This interface is designed to be compatible with Java 21's stricter class-loading semantics
 * and leverages modern Java features for improved type safety and performance.
 *
 * @param <T> The data type of the field. Implementations should specify concrete types
 *            to ensure proper type checking under Java 21's enhanced type system.
 * 
 * @since 3.0
 */
public interface FormField<T>
{
  /**
   * Mandatory ({@code true}) symbol.
   *
   * @see #isRequired()
   */
  boolean MANDATORY = true;

  /**
   * Optional ({@code false}) symbol.
   *
   * @see #isRequired()
   */
  boolean OPTIONAL = false;

  /**
   * Field type.
   *
   * This is a symbolic type to match up the widget implementation for the field in the UI.
   */
  String getType();

  /**
   * Field label.
   */
  String getLabel();

  /**
   * Field identifier.
   */
  String getId();

  /**
   * True if field is required.
   */
  boolean isRequired();

  /**
   * True if field is disabled.
   */
  boolean isDisabled();

  /**
   * True if field can only read.
   */
  boolean isReadOnly();

  /**
   * Help text of field.
   */
  String getHelpText();

  /**
   * Optional regular-expression to validate field.
   */
  @Nullable
  String getRegexValidation();

  /**
   * Optional initial value of the field.
   *
   * @since 2.3
   */
  @Nullable
  T getInitialValue();

  /**
   * Optional field attributes.
   *
   * Used to encode additional data to widget implementation in UI.
   *
   * Care must be used to ensure that values are transferable, and likely should remain simple values,
   * collections of simple values or simple transfer objects.
   * 
   * With Java 21's enhanced type checking, implementations should ensure proper type safety
   * when adding values to this map. Consider using {@link #getAttribute(String, Class)} for
   * type-safe attribute retrieval.
   *
   * @since 3.1
   */
  Map<String,Object> getAttributes();

  /**
   * Determines if the field allows browser autocomplete functionality.
   * 
   * @return {@code true} if autocomplete is allowed, {@code false} otherwise
   */
  default boolean getAllowAutocomplete() {
    return false;
  }
  
  /**
   * Type-safe accessor for retrieving attributes with the expected type.
   * 
   * This method leverages Java 21's enhanced type checking to provide safer attribute access.
   * It returns an Optional to handle the case where the attribute doesn't exist or is of the wrong type.
   *
   * @param <V> The expected type of the attribute value
   * @param key The attribute key
   * @param type The class representing the expected type
   * @return An Optional containing the attribute value if it exists and matches the expected type,
   *         or an empty Optional otherwise
   * @since 3.60
   */
  @SuppressWarnings("unchecked")
  default <V> Optional<V> getAttribute(String key, Class<V> type) {
    Object value = getAttributes().get(key);
    if (value != null && type.isInstance(value)) {
      return Optional.of((V) value);
    }
    return Optional.empty();
  }
  
  /**
   * Type-safe accessor for retrieving string attributes.
   * 
   * This is a convenience method for the common case of string attributes.
   *
   * @param key The attribute key
   * @return An Optional containing the string value if it exists,
   *         or an empty Optional otherwise
   * @since 3.60
   */
  default Optional<String> getStringAttribute(String key) {
    return getAttribute(key, String.class);
  }
  
  /**
   * Type-safe accessor for retrieving boolean attributes.
   * 
   * This is a convenience method for the common case of boolean attributes.
   *
   * @param key The attribute key
   * @return An Optional containing the boolean value if it exists,
   *         or an empty Optional otherwise
   * @since 3.60
   */
  default Optional<Boolean> getBooleanAttribute(String key) {
    return getAttribute(key, Boolean.class);
  }
}