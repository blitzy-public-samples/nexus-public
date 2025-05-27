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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;

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
   * Record representing a mapping pair for id and name fields.
   * 
   * @since 3.60
   */
  public record MappingPair(String idMapping, String nameMapping) {}

  private String storeApi;

  private final Map<String, String> storeFilters;

  private String idMapping;

  private String nameMapping;
  
  /**
   * Virtual thread executor for asynchronous data fetching operations.
   * Using virtual threads improves scalability for I/O-bound operations.
   * 
   * @since 3.60
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
   * Returns the current id/name mapping as a MappingPair record.
   * 
   * @return MappingPair containing the current id and name mappings
   * @since 3.60
   */
  public MappingPair getMappingPair() {
    return new MappingPair(idMapping, nameMapping);
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
   * Processes a filter value based on its type using pattern matching.
   * This method demonstrates the use of Pattern Matching for switch to improve
   * code readability and maintainability.
   *
   * @param filterValue the filter value to process
   * @return processed filter value as string
   * @since 3.60
   */
  public String processFilterValue(Object filterValue) {
    return switch (filterValue) {
      case String s -> s;
      case Number n -> n.toString();
      case Boolean b -> b.toString();
      case null -> "";
      case Optional<?> o -> o.map(Object::toString).orElse("");
      default -> filterValue.toString();
    };
  }
  
  /**
   * Applies a filter transformation function to all filter values.
   * Uses modern collection methods for map processing.
   *
   * @param transformer function to transform filter values
   * @return this instance for method chaining
   * @since 3.60
   */
  public ComboboxFormField<V> transformFilters(Function<String, String> transformer) {
    checkNotNull(transformer, "transformer");
    
    Map<String, String> transformedFilters = new HashMap<>();
    storeFilters.forEach((key, value) -> 
        transformedFilters.put(key, transformer.apply(value)));
    
    storeFilters.clear();
    storeFilters.putAll(transformedFilters);
    
    return this;
  }

  /**
   * Asynchronously fetches data from the store API using Virtual Threads.
   * This improves scalability for I/O-bound operations by using lightweight virtual threads
   * instead of platform threads.
   *
   * @param dataFetcher function that performs the actual data fetching operation
   * @param <R> the type of data being fetched
   * @return CompletableFuture that will complete with the fetched data
   * @since 3.60
   */
  public <R> CompletableFuture<R> fetchDataAsync(Function<ComboboxFormField<V>, R> dataFetcher) {
    checkNotNull(dataFetcher, "dataFetcher");
    return CompletableFuture.supplyAsync(() -> dataFetcher.apply(this), VIRTUAL_THREAD_EXECUTOR);
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
   * Sets both id and name mappings using a MappingPair record.
   * Demonstrates the use of Record Patterns for more concise data handling.
   *
   * @param mappingPair record containing id and name mapping values
   * @return this instance for method chaining
   * @since 3.60
   */
  public ComboboxFormField<V> withMappingPair(MappingPair mappingPair) {
    if (mappingPair instanceof MappingPair(String id, String name)) {
      this.idMapping = id;
      this.nameMapping = name;
    }
    return this;
  }
}
