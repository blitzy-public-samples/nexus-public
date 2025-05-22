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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A mutable combo-box {@link FormField}.
 *
 * @since 2.7
 */
public class ComboboxFormField<V>
    extends Combobox<V>
{
  /**
   * Record for id/name mapping pairs to simplify data handling.
   *
   * @since 3.31
   */
  public record MappingPair(String id, String name) {}

  private String storeApi;

  private final Map<String, String> storeFilters;

  private String idMapping;

  private String nameMapping;
  
  /**
   * Virtual thread executor for remote data fetching operations.
   *
   * @since 3.31
   */
  private static final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public ComboboxFormField(final String id,
                           final String label,
                           final String helpText,
                           final boolean required,
                           final V initialValue)
  {
    super(id, label, helpText, required, initialValue);
    this.storeFilters = new HashMap<>();
  }

  public ComboboxFormField(final String id,
                           final String label,
                           final String helpText,
                           final boolean required)
  {
    this(id, label, helpText, required, null);
  }

  public ComboboxFormField(final String id,
                           final String label,
                           final String helpText)
  {
    this(id, label, helpText, OPTIONAL);
  }

  public ComboboxFormField(final String id,
                           final String label)
  {
    this(id, label, null);
  }

  /**
   * @since 3.0
   */
  @Override
  public String getStoreApi() {
    return storeApi;
  }

  /**
   * @since 3.0
   */
  @Override
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
   * @since 3.0
   */
  public ComboboxFormField<V> withStoreApi(final String storeApi) {
    this.storeApi = checkNotNull(storeApi);
    return this;
  }

  /**
   * Adds a store filter.
   *
   * @param property filter property
   * @param value    filter value
   * @since 3.0
   */
  public Combobox<V> withStoreFilter(final String property, final String value) {
    storeFilters.put(checkNotNull(property, "property"), checkNotNull(value, "value"));
    return this;
  }

  /**
   * Adds multiple store filters at once.
   *
   * @param filters map of property/value pairs to add as filters
   * @return this instance for fluent API usage
   * @since 3.31
   */
  public Combobox<V> withStoreFilters(final Map<String, String> filters) {
    if (filters != null && !filters.isEmpty()) {
      filters.forEach(this::withStoreFilter);
    }
    return this;
  }

  /**
   * Clears all store filters.
   *
   * @return this instance for fluent API usage
   * @since 3.31
   */
  public Combobox<V> clearStoreFilters() {
    storeFilters.clear();
    return this;
  }

  /**
   * Sets both id and name mappings at once using a MappingPair record.
   *
   * @param mappingPair the id/name mapping pair
   * @return this instance for fluent API usage
   * @since 3.31
   */
  public ComboboxFormField<V> withMappings(final MappingPair mappingPair) {
    if (mappingPair != null) {
      this.idMapping = mappingPair.id();
      this.nameMapping = mappingPair.name();
    }
    return this;
  }

  public ComboboxFormField<V> withIdMapping(final String idMapping) {
    this.idMapping = idMapping;
    return this;
  }

  public ComboboxFormField<V> withNameMapping(final String nameMapping) {
    this.nameMapping = nameMapping;
    return this;
  }

  /**
   * Fetches data from the remote API using Virtual Threads.
   * 
   * @param <T> the type of data to be returned
   * @param dataFetcher the function to fetch data from the remote API
   * @return a CompletableFuture that will complete with the fetched data
   * @since 3.31
   */
  public <T> CompletableFuture<List<T>> fetchDataAsync(DataFetcher<T> dataFetcher) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return dataFetcher.fetch(storeApi, getStoreFilters());
      } catch (Exception e) {
        return Collections.emptyList();
      }
    }, virtualThreadExecutor);
  }

  /**
   * Functional interface for fetching data from a remote API.
   *
   * @param <T> the type of data to be returned
   * @since 3.31
   */
  @FunctionalInterface
  public interface DataFetcher<T> {
    /**
     * Fetches data from the remote API.
     *
     * @param api the API endpoint to fetch data from
     * @param filters the filters to apply to the data fetch
     * @return the fetched data
     * @throws Exception if an error occurs during data fetching
     */
    List<T> fetch(String api, Map<String, String> filters) throws Exception;
  }

  /**
   * Processes a value based on its type using Pattern Matching for switch.
   *
   * @param value the value to process
   * @return a string representation of the value
   * @since 3.31
   */
  public String processFilterValue(Object value) {
    return switch (value) {
      case String s -> s;
      case Number n -> n.toString();
      case Boolean b -> b.toString();
      case null -> "";
      default -> value.toString();
    };
  }
}
