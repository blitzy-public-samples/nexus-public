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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * An item-selection {@link FormField}.
 *
 * @since 3.1
 */
public class ItemselectFormField
    extends AbstractFormField<String>
    implements Selectable
{
  public static final String TYPE = "itemselect";

  private static final String ATTRIBUTE_LISTENERS = "listeners";

  /**
   * Record representing a field mapping pair (id and name).
   * Used for type-safe handling of mapping data with record patterns.
   */
  public record FieldMapping(String idField, String nameField) {}

  private String storeApi;

  private final Map<String, String> storeFilters = new HashMap<>();

  private String idMapping;

  private String nameMapping;

  public ItemselectFormField(
      final String id,
      final String label,
      final String helpText,
      final boolean required,
      final String initialValue)
  {
    super(id, label, helpText, required, null, initialValue);
  }

  public ItemselectFormField(
      final String id,
      final String label,
      final String helpText,
      final boolean required)
  {
    this(id, label, helpText, required, null);
  }

  public ItemselectFormField(
      final String id,
      final String label,
      final String helpText)
  {
    this(id, label, helpText, OPTIONAL);
  }

  public ItemselectFormField(
      final String id,
      final String label)
  {
    this(id, label, null);
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getStoreApi() {
    return storeApi;
  }

  @Override
  @Nullable
  public Map<String, String> getStoreFilters() {
    return storeFilters.isEmpty() ? null : Map.copyOf(storeFilters);
  }

  @Override
  public String getIdMapping() {
    return idMapping;
  }

  @Override
  public String getNameMapping() {
    return nameMapping;
  }

  /**
   * Returns the field mapping as a record for type-safe handling.
   * 
   * @return a FieldMapping record containing id and name field mappings
   */
  public FieldMapping getFieldMapping() {
    return new FieldMapping(idMapping, nameMapping);
  }

  /**
   * Asynchronously loads data from the configured store API using Virtual Threads.
   * This method leverages Java 21 Virtual Threads for improved concurrency with minimal overhead.
   *
   * @param dataLoader the function that performs the actual data loading operation
   * @param <T> the type of data being returned
   * @return a Future containing the loaded data
   */
  public <T> Future<T> loadDataAsync(Function<ItemselectFormField, T> dataLoader) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> dataLoader.apply(this));
  }

  public void setStoreApi(final String storeApi) {
    this.storeApi = storeApi;
  }

  public void setIdMapping(final String idMapping) {
    this.idMapping = idMapping;
  }

  public void setNameMapping(final String nameMapping) {
    this.nameMapping = nameMapping;
  }

  /**
   * Sets both id and name mappings from a FieldMapping record.
   * Demonstrates the use of record patterns for concise field extraction.
   *
   * @param mapping the field mapping record
   */
  public void setFieldMapping(FieldMapping mapping) {
    // Using record pattern to extract fields
    if (mapping instanceof FieldMapping(var id, var name)) {
      this.idMapping = id;
      this.nameMapping = name;
    }
  }

  public void addStoreFilter(final String property, final String value) {
    storeFilters.put(property, value);
  }

  /**
   * Adds multiple filters at once using modern collection operations.
   *
   * @param filters the map of filters to add
   */
  public void addStoreFilters(Map<String, String> filters) {
    filters.forEach(this.storeFilters::put);
  }

  public void setButtons(final String... buttons) {
    getAttributes().put("buttons", buttons);
  }

  public void setFromTitle(final String title) {
    getAttributes().put("fromTitle", title);
  }

  public void setToTitle(final String title) {
    getAttributes().put("toTitle", title);
  }

  public void setValueAsString(final boolean valueAsString) {
    getAttributes().put("valueAsString", valueAsString);
  }

  public ItemselectFormField withStoreApi(final String storeApi) {
    this.storeApi = storeApi;
    return this;
  }

  public ItemselectFormField withIdMapping(final String idMapping) {
    this.idMapping = idMapping;
    return this;
  }

  public ItemselectFormField withNameMapping(final String nameMapping) {
    this.nameMapping = nameMapping;
    return this;
  }

  /**
   * Fluent API for setting both id and name mappings from a FieldMapping record.
   *
   * @param mapping the field mapping record
   * @return this instance for method chaining
   */
  public ItemselectFormField withFieldMapping(FieldMapping mapping) {
    setFieldMapping(mapping);
    return this;
  }

  public ItemselectFormField withStoreFilter(final String property, final String value) {
    storeFilters.put(property, value);
    return this;
  }

  /**
   * Fluent API for adding multiple filters at once using modern collection operations.
   *
   * @param filters the map of filters to add
   * @return this instance for method chaining
   */
  public ItemselectFormField withStoreFilters(Map<String, String> filters) {
    filters.forEach(this.storeFilters::put);
    return this;
  }

  public ItemselectFormField withButtons(final String... buttons) {
    getAttributes().put("buttons", buttons);
    return this;
  }

  public ItemselectFormField withFromTitle(final String title) {
    getAttributes().put("fromTitle", title);
    return this;
  }

  public ItemselectFormField withToTitle(final String title) {
    getAttributes().put("toTitle", title);
    return this;
  }

  public ItemselectFormField withValueAsString(final boolean valueAsString) {
    getAttributes().put("valueAsString", valueAsString);
    return this;
  }

  /**
   * Configure a listener that will be bound to an event during component initialization.
   * The listener function must be declared in NX.coreui.view.formfield.SettingsFieldSet
   * 
   * @param eventName Any event name from Ext.ux.form.ItemSelector
   * @param listenerName Name of the property that contains the handler method. Must be defined in
   *          NX.coreui.view.formfield.SettingsFieldSet
   * @return This instance
   */
  public ItemselectFormField withListener(final String eventName, final String listenerName) {
    // Using pattern matching for instanceof check with type casting
    Map<String, String> declaredListeners = getAttributes().get(ATTRIBUTE_LISTENERS) instanceof Map<?, ?> listeners
        ? (Map<String, String>) listeners
        : new HashMap<>();

    declaredListeners.put(eventName, listenerName);
    getAttributes().put(ATTRIBUTE_LISTENERS, declaredListeners);

    return this;
  }

  public ItemselectFormField withSelectionPlaceholderText(final String value) {
    getAttributes().put("selectionPlaceholderText", value);
    return this;
  }

  /**
   * Returns a string representation of this form field using Java 21 String Templates.
   * 
   * @return a string representation of this form field
   */
  @Override
  public String toString() {
    return STR."ItemselectFormField{id=\{getId()}, label=\{getLabel()}, storeApi=\{storeApi}, "
        + STR."idMapping=\{idMapping}, nameMapping=\{nameMapping}, filters=\{storeFilters.size()}}";
  }
}
