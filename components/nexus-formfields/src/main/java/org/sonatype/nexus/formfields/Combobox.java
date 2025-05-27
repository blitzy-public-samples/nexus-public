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

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * Combo-box {@link FormField} support.
 * 
 * <p>
 * This class has been updated for Java 21 compatibility with the following enhancements:
 * <ul>
 *   <li>Refined generic type parameters for improved type safety</li>
 *   <li>String Templates for error messages and debug output</li>
 *   <li>Improved validation with descriptive error messages</li>
 * </ul>
 * </p>
 *
 * @param <V> The value type for this combobox, must be Serializable for proper transport
 * @since 2.7
 */
public abstract class Combobox<V extends Serializable>
    extends AbstractFormField<V>
    implements Selectable
{
  /**
   * Default store API value when none is specified.
   * 
   * @since 3.60
   */
  private static final String DEFAULT_STORE_API = null;

  public Combobox(final String id,
                  final String label,
                  final String helpText,
                  final boolean required,
                  final V initialValue)
  {
    super(id, label, helpText, required, null, initialValue);
    validateConstructorParams(id, label);
  }

  public Combobox(final String id,
                  final String label,
                  final String helpText,
                  final boolean required)
  {
    this(id, label, helpText, required, null);
  }

  public Combobox(final String id,
                  final String label,
                  final String helpText)
  {
    this(id, label, helpText, OPTIONAL);
  }

  public Combobox(final String id,
                  final String label)
  {
    this(id, label, null);
  }
  
  /**
   * Validates constructor parameters using String Templates for error messages.
   * 
   * @param id the field ID to validate
   * @param label the field label to validate
   * @throws NullPointerException if any required parameter is null
   * @since 3.60
   */
  private void validateConstructorParams(String id, String label) {
    if (id == null) {
      throw new NullPointerException(STR."Field ID cannot be null for combobox: \{label}");
    }
    if (label == null) {
      throw new NullPointerException(STR."Field label cannot be null for combobox with ID: \{id}");
    }
  }

  @Override
  public String getType() {
    return "combobox";
  }
  
  /**
   * Returns the store API for this combobox.
   * Implementations should override this method to provide a specific store API.
   * 
   * @return the store API or null if not specified
   * @since 3.60
   */
  @Override
  public String getStoreApi() {
    return DEFAULT_STORE_API;
  }
  
  /**
   * Returns the store filters for this combobox.
   * Implementations should override this method to provide specific store filters.
   * 
   * @return the store filters or null if not specified
   * @since 3.60
   */
  @Override
  public Map<String, String> getStoreFilters() {
    return null;
  }

  @Override
  public String getIdMapping() {
    return null;
  }

  @Override
  public String getNameMapping() {
    return null;
  }

  /**
   * Sets the ID for this combobox.
   * 
   * @param id the ID to set
   * @return this combobox instance for method chaining
   * @throws NullPointerException if id is null
   */
  public Combobox<V> withId(final String id) {
    Objects.requireNonNull(id, STR."ID cannot be null for combobox: \{getLabel()}");
    setId(id);
    return this;
  }

  /**
   * Sets the label for this combobox.
   * 
   * @param label the label to set
   * @return this combobox instance for method chaining
   * @throws NullPointerException if label is null
   */
  public Combobox<V> withLabel(final String label) {
    Objects.requireNonNull(label, STR."Label cannot be null for combobox with ID: \{getId()}");
    setLabel(label);
    return this;
  }

  /**
   * Sets the help text for this combobox.
   * 
   * @param helpText the help text to set
   * @return this combobox instance for method chaining
   */
  public Combobox<V> withHelpText(final String helpText) {
    setHelpText(helpText);
    return this;
  }

  /**
   * Sets the regex validation pattern for this combobox.
   * 
   * @param regex the regex validation pattern to set
   * @return this combobox instance for method chaining
   */
  public Combobox<V> withRegexValidation(final String regex) {
    setRegexValidation(regex);
    return this;
  }

  /**
   * Sets whether this combobox is required.
   * 
   * @param required true if the combobox is required, false otherwise
   * @return this combobox instance for method chaining
   */
  public Combobox<V> withRequired(final boolean required) {
    setRequired(required);
    return this;
  }

  /**
   * Makes this combobox optional.
   * 
   * @return this combobox instance for method chaining
   */
  public Combobox<V> optional() {
    return withRequired(OPTIONAL);
  }

  /**
   * Makes this combobox mandatory.
   * 
   * @return this combobox instance for method chaining
   */
  public Combobox<V> mandatory() {
    return withRequired(MANDATORY);
  }

  /**
   * Sets the initial value for this combobox.
   * 
   * @param value the initial value to set
   * @return this combobox instance for method chaining
   */
  public Combobox<V> withInitialValue(final V value) {
    setInitialValue(value);
    return this;
  }
  
  /**
   * Returns a string representation of this combobox using String Templates.
   * 
   * @return a string representation of this combobox
   * @since 3.60
   */
  @Override
  public String toString() {
    return STR."Combobox{id=\{getId()}, label=\{getLabel()}, required=\{isRequired()}, "
        + STR."readOnly=\{isReadOnly()}, disabled=\{isDisabled()}, type=\{getType()}}"; 
  }
}
