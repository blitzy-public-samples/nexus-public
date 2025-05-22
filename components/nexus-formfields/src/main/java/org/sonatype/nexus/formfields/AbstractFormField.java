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

import java.util.Collections;
import java.util.Map;

/**
 * Support for {@link FormField} implementations.
 */
public abstract class AbstractFormField<T extends Object>
    implements FormField<T>
{
  private String helpText;

  private String id;

  private String regexValidation;

  private boolean required;

  private boolean disabled;

  private boolean readOnly;

  private String label;

  private T initialValue;

  /**
   * @since 3.1
   */
  private Map<String,Object> attributes;

  // NOTE: avoid adding anymore constructors

  public AbstractFormField(final String id,
                           final String label,
                           final String helpText,
                           final boolean required,
                           final String regexValidation,
                           final T initialValue)
  {
    this(id, label, helpText, required, regexValidation);
    this.initialValue = initialValue;
  }

  public AbstractFormField(final String id,
                           final String label,
                           final String helpText,
                           final boolean required,
                           final String regexValidation)
  {
    this(id, label, helpText, required);
    this.regexValidation = regexValidation;
  }

  public AbstractFormField(final String id,
                           final String label,
                           final String helpText,
                           final boolean required)
  {
    this(id);
    this.label = label;
    this.helpText = helpText;
    this.required = required;
  }

  public AbstractFormField(final String id) {
    this.id = id;
  }

  public String getLabel() {
    return this.label;
  }

  public String getHelpText() {
    return this.helpText;
  }

  public String getId() {
    return this.id;
  }

  public String getRegexValidation() {
    return this.regexValidation;
  }

  public boolean isRequired() {
    return this.required;
  }

  public boolean isDisabled() {
    return this.disabled;
  }

  public boolean isReadOnly() {
    return this.readOnly;
  }

  public T getInitialValue() {
    return initialValue;
  }

  public void setHelpText(final String helpText) {
    this.helpText = helpText;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public void setRegexValidation(final String regex) {
    this.regexValidation = regex;
  }

  public void setRequired(final boolean required) {
    this.required = required;
  }

  public void setDisabled(final boolean disabled) {
    this.disabled = disabled;
  }

  public void setReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
  }

  public void setLabel(final String label) {
    this.label = label;
  }

  public void setInitialValue(final T value) {
    this.initialValue = value;
  }

  /**
   * @since 3.1
   */
  @Override
  public Map<String, Object> getAttributes() {
    if (attributes == null) {
      attributes = Collections.emptyMap();
    }
    return attributes;
  }

  public AbstractFormField<T> withAttribute(String key, Object value) {
    if (attributes == null || attributes.isEmpty()) {
      attributes = Map.of(key, value);
    } else {
      // Create a mutable copy if we need to add more attributes
      if (attributes.size() == 1 && attributes instanceof Map.Entry) {
        var entry = (Map.Entry<String, Object>) attributes;
        attributes = Map.of(entry.getKey(), entry.getValue(), key, value);
      } else if (attributes.size() == 2 && !(attributes instanceof Collections.UnmodifiableMap)) {
        // For 3 entries, use Map.of
        var entries = attributes.entrySet().toArray(new Map.Entry[0]);
        attributes = Map.of(
            entries[0].getKey(), entries[0].getValue(),
            entries[1].getKey(), entries[1].getValue(),
            key, value);
      } else {
        // For more entries or if we already have an unmodifiable map, create a mutable copy
        var newAttributes = new java.util.HashMap<>(attributes);
        newAttributes.put(key, value);
        attributes = Collections.unmodifiableMap(newAttributes);
      }
    }
    return this;
  }
  
  /**
   * Returns a string representation of this form field.
   * 
   * @return a string representation of this form field
   * @since 3.31
   */
  @Override
  public String toString() {
    return STR."AbstractFormField{id=\{id}, label=\{label}, required=\{required}, readOnly=\{readOnly}, disabled=\{disabled}}";
  }
}