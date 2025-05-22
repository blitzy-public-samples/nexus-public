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
import javax.annotation.Nullable;

/**
 * Combo-box {@link FormField} support.
 *
 * @param <V> The value type for the combobox, must be serializable for proper data transfer
 * @since 2.7
 */
public abstract class Combobox<V extends Serializable>
    extends AbstractFormField<V>
    implements Selectable
{

  public Combobox(final String id,
                  final String label,
                  final String helpText,
                  final boolean required,
                  final V initialValue)
  {
    super(id, label, helpText, required, null, initialValue);
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

  @Override
  public String getType() {
    return "combobox";
  }
  
  /**
   * Returns the store API for this combobox.
   * Must be implemented by concrete subclasses to specify the data source.
   *
   * @return the Ext.Direct API name used to configure Ext proxy
   */
  @Override
  public abstract String getStoreApi();

  /**
   * Returns filters to be applied to the store.
   * Can be overridden by subclasses to provide specific filtering.
   *
   * @return filters to be applied to the store or null if no filtering is needed
   */
  @Override
  @Nullable
  public Map<String, String> getStoreFilters() {
    return null;
  }

  @Override
  public String getIdMapping() {
    return "id";
  }

  @Override
  public String getNameMapping() {
    return "name";
  }

  public Combobox<V> withId(final String id) {
    setId(id);
    return this;
  }

  public Combobox<V> withLabel(final String label) {
    setLabel(label);
    return this;
  }

  public Combobox<V> withHelpText(final String helpText) {
    setHelpText(helpText);
    return this;
  }

  public Combobox<V> withRegexValidation(final String regex) {
    setRegexValidation(regex);
    return this;
  }

  public Combobox<V> withRequired(final boolean required) {
    setRequired(required);
    return this;
  }

  public Combobox<V> optional() {
    return withRequired(OPTIONAL);
  }

  public Combobox<V> mandatory() {
    return withRequired(MANDATORY);
  }

  public Combobox<V> withInitialValue(final V value) {
    setInitialValue(value);
    return this;
  }
  
  /**
   * Adds a custom attribute to this combobox.
   *
   * @param key the attribute key
   * @param value the attribute value
   * @return this combobox instance for method chaining
   */
  public Combobox<V> withAttribute(String key, Object value) {
    return (Combobox<V>) super.withAttribute(key, value);
  }
}
