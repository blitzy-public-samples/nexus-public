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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

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
   * Record representing a mapping pair for ID and name.
   * Used for type-safe handling of mapping data.
   *
   * @since 3.60
   */
public record MappingPair(String idMapping, String nameMapping) {}

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
   * Get the mapping pair containing both ID and name mappings.
   *
   * @return a record containing both mappings
   * @since 3.60
   */
  public MappingPair getMappingPair() {
    return new MappingPair(idMapping, nameMapping);
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
   * Set both ID and name mappings at once using a mapping pair.
   *
   * @param mappingPair the record containing both mappings
   * @since 3.60
   */
  public void setMappingPair(final MappingPair mappingPair) {
    if (mappingPair instanceof MappingPair(var id, var name)) {
      this.idMapping = id;
      this.nameMapping = name;
    }
  }

  public void addStoreFilter(final String property, final String value) {
    storeFilters.put(property, value);
  }

  /**
   * Asynchronously load data from the store API using virtual threads.
   *
   * @param dataLoader the function to load data from the store API
   * @param <T> the type of data to be loaded
   * @return a CompletableFuture containing the loaded data
   * @since 3.60
   */
  public <T> CompletableFuture<T> loadDataAsync(final Function<String, T> dataLoader) {
    return CompletableFuture.supplyAsync(() -> {
      if (storeApi == null) {
        throw new IllegalStateException(STR."Store API not configured for field \{getId()}");
      }
      return dataLoader.apply(storeApi);
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Asynchronously load data with filters from the store API using virtual threads.
   *
   * @param dataLoader the function to load data from the store API with filters
   * @param <T> the type of data to be loaded
   * @return a CompletableFuture containing the loaded data
   * @since 3.60
   */
  public <T> CompletableFuture<T> loadDataWithFiltersAsync(final Function<Map<String, String>, T> dataLoader) {
    return CompletableFuture.supplyAsync(() -> {
      if (storeApi == null) {
        throw new IllegalStateException(STR."Store API not configured for field \{getId()}");
      }
      return dataLoader.apply(getStoreFilters() != null ? getStoreFilters() : Map.of());
    }, Executors.newVirtualThreadPerTaskExecutor());
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
   * Fluent API for setting both ID and name mappings at once using a mapping pair.
   *
   * @param mappingPair the record containing both mappings
   * @return this instance for method chaining
   * @since 3.60
   */
  public ItemselectFormField withMappingPair(final MappingPair mappingPair) {
    setMappingPair(mappingPair);
    return this;
  }

  public ItemselectFormField withStoreFilter(final String property, final String value) {
    storeFilters.put(property, value);
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
    Map<String, String> declaredListeners = getAttributes().containsKey(ATTRIBUTE_LISTENERS)
        ? (Map<String, String>) getAttributes().get(ATTRIBUTE_LISTENERS)
        : new HashMap<>();

    declaredListeners.put(eventName, listenerName);
    getAttributes().put(ATTRIBUTE_LISTENERS, declaredListeners);

    return this;
  }

  /**
   * Configure multiple listeners at once using a map.
   *
   * @param listeners a map of event names to listener names
   * @return this instance for method chaining
   * @since 3.60
   */
  public ItemselectFormField withListeners(final Map<String, String> listeners) {
    Map<String, String> declaredListeners = getAttributes().containsKey(ATTRIBUTE_LISTENERS)
        ? (Map<String, String>) getAttributes().get(ATTRIBUTE_LISTENERS)
        : new HashMap<>();

    declaredListeners.putAll(listeners);
    getAttributes().put(ATTRIBUTE_LISTENERS, declaredListeners);

    return this;
  }

  public ItemselectFormField withSelectionPlaceholderText(final String value) {
    getAttributes().put("selectionPlaceholderText", value);
    return this;
  }
}