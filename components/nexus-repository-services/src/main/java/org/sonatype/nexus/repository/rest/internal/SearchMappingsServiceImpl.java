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
package org.sonatype.nexus.repository.rest.internal;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.rest.SearchMappings;
import org.sonatype.nexus.repository.rest.SearchMappingsService;

import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;

@Named
@Singleton
public class SearchMappingsServiceImpl
    extends ComponentSupport
    implements SearchMappingsService
{
  private static final String DEFAULT = "default";

  private final Collection<SearchMapping> searchMappings;

  @Inject
  public SearchMappingsServiceImpl(final Map<String, SearchMappings> searchMappings) {
	 Map<String, SearchMappings> validatedMappings = requireNonNull(searchMappings, "searchMappings cannot be null");
    this.searchMappings = collectMappings(validatedMappings);
  }

  /**
   * Collects search mappings from all providers, processing the default provider first
   * and then concurrently processing all other providers using Virtual Threads.
   *
   * @param searchMappings Map of provider names to their SearchMappings implementations
   * @return An immutable collection of all SearchMapping instances
   */
  private static Collection<SearchMapping> collectMappings(final Map<String, SearchMappings> searchMappings) {
    ImmutableList.Builder<SearchMapping> builder = ImmutableList.builder();

    // Process default mappings first
    searchMappings.entrySet().stream()
        .filter(entry -> DEFAULT.equals(entry.getKey()))
        .findFirst()
        .ifPresent(entry -> {
          builder.addAll(entry.getValue().get());
        });

    // add the rest of the mappings
    searchMappings.keySet().stream()
        .filter(key -> !DEFAULT.equals(key))
        .sorted()
        .forEach(key -> builder.addAll(searchMappings.get(key).get()));

    return builder.build();
  }

  @Override
  public Collection<SearchMapping> getAllMappings() {
    return searchMappings;
  }
}