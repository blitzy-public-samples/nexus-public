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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.sonatype.goodies.i18n.I18N;
import org.sonatype.goodies.i18n.MessageBundle;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import static java.lang.StringTemplate.STR;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Stream.concat;

/**
 * A repository combo box {@link FormField}.
 *
 * @since 2.7
 */
public class RepositoryCombobox
    extends Combobox<String>
{
  /**
   * Record for repository information with id and name.
   * Used for type-safe handling of repository data.
   *
   * @since 3.60
   */
  public record RepositoryInfo(String id, String name) {}

  private List<String> includingTypes;

  private List<String> excludingTypes;

  private boolean regardlessViewPermissions;

  private List<String> includingFormats;

  private List<String> excludingFormats;

  private List<String> includingVersionPolicies;

  private List<String> excludingVersionPolicies;

  private List<String> includingFacets;

  private boolean generateAllRepositoriesEntry;

  private boolean includeEntriesForAllFormats;

  private interface Messages
      extends MessageBundle
  {

    @DefaultMessage("Repository")
    String label();

    @DefaultMessage("Select the repository.")
    String helpText();

  }

  private static final Messages messages = I18N.create(Messages.class);

  public RepositoryCombobox(String id, String label, String helpText, boolean required, String regexValidation) {
    super(id, label, helpText, required, regexValidation);
  }

  public RepositoryCombobox(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public RepositoryCombobox(String id, boolean required) {
    super(id, messages.label(), messages.helpText(), required);
  }

  public RepositoryCombobox(String id) {
    super(id, messages.label(), messages.helpText(), false);
  }

  /**
   * Repository will be present if is of any of specified types.
   */
  public RepositoryCombobox includingAnyOfTypes(final String... types) {
    this.includingTypes = Arrays.asList(types);
    return this;
  }

  /**
   * Repository will not be present if is of any of specified types.
   */
  public RepositoryCombobox excludingAnyOfTypes(final String... types) {
    this.excludingTypes = Arrays.asList(types);
    return this;
  }

  /**
   * Repository will be present if is of any of specified formats.
   */
  public RepositoryCombobox includingAnyOfFormats(final String... formats) {
    this.includingFormats = Arrays.asList(formats);
    return this;
  }

  /**
   * Repository will not be present if is of any of specified formats.
   */
  public RepositoryCombobox excludingAnyOfFormats(final String... formats) {
    this.excludingFormats = Arrays.asList(formats);
    return this;
  }

  /**
   * Repository will be present if is of any of specified Version Policies.
   */
  public RepositoryCombobox includingAnyOfVersionPolicies(final String... versionPolicies) {
    this.includingVersionPolicies = Arrays.asList(versionPolicies);
    return this;
  }

  /**
   * Repository will not be present if is of any of specified Version Policies.
   */
  public RepositoryCombobox excludingAnyOfVersionPolicies(final String... versionPolicies) {
    this.excludingVersionPolicies = Arrays.asList(versionPolicies);
    return this;
  }

  /**
   * Repository will be present if is of any of specified formats.
   */
  public RepositoryCombobox includingAnyOfFacets(final Class<?>... facets) {
    this.includingFacets = Lists.transform(Arrays.asList(facets), Class::getName);
    return this;
  }

  /**
   * Repository will be present regardless if current user has rights to view the repository.
   */
  public RepositoryCombobox regardlessViewPermissions() {
    this.regardlessViewPermissions = true;
    return this;
  }

  /**
   * Will add an entry for "All repositories". The value will be "*".
   */
  public RepositoryCombobox includeAnEntryForAllRepositories() {
    this.generateAllRepositoriesEntry = true;
    return this;
  }

  /**
   * Will add an entry for "All Repositories" as well as "All nuget repositories", "All npm repositories", etc.
   *
   * @since 3.1
   */
  public RepositoryCombobox includeEntriesForAllFormats() {
    this.includeEntriesForAllFormats = true;
    return this;
  }

  @Override
  public boolean getAllowAutocomplete() {
    return true;
  }

  /**
   * @since 3.0
   */
  @Override
  public String getStoreApi() {
    return switch (true) {
      case includeEntriesForAllFormats -> "coreui_Repository.readReferencesAddingEntriesForAllFormats";
      case generateAllRepositoriesEntry -> "coreui_Repository.readReferencesAddingEntryForAll";
      default -> "coreui_Repository.readReferences";
    };
  }

  /**
   * @since 3.0
   */
  @Override
  public Map<String, String> getStoreFilters() {
    Map<String, String> storeFilters = Maps.newHashMap();
    
    // Build type filter string using String Templates
    String types = buildTypeFilterString();
    if (!types.isEmpty()) {
      storeFilters.put("type", types);
    }
    
    // Build format filter string using String Templates
    String contentClasses = buildFormatFilterString();
    if (!contentClasses.isEmpty()) {
      storeFilters.put("format", contentClasses);
    }
    
    // Get version policies filter
    String versionPolicies = getVersionPolicies();
    if (!versionPolicies.isEmpty()) {
      storeFilters.put("versionPolicies", versionPolicies);
    }
    
    // Add facets filter if present
    if (includingFacets != null) {
      storeFilters.put("facets", String.join(",", includingFacets));
    }
    
    // Add view permissions filter if needed
    if (regardlessViewPermissions) {
      storeFilters.put("regardlessViewPermissions", "true");
    }
    
    return storeFilters.isEmpty() ? null : storeFilters;
  }

  /**
   * Builds the type filter string using String Templates for improved readability.
   * 
   * @return The type filter string
   * @since 3.60
   */
  private String buildTypeFilterString() {
    StringBuilder types = new StringBuilder();
    
    // Add including types
    if (includingTypes != null) {
      types.append(String.join(",", includingTypes));
    }
    
    // Add excluding types with ! prefix
    if (excludingTypes != null) {
      if (types.length() > 0 && !excludingTypes.isEmpty()) {
        types.append(",");
      }
      String excludes = excludingTypes.stream()
          .map(type -> STR."!\{type}")
          .collect(joining(","));
      types.append(excludes);
    }
    
    return types.toString();
  }

  /**
   * Builds the format filter string using String Templates for improved readability.
   * 
   * @return The format filter string
   * @since 3.60
   */
  private String buildFormatFilterString() {
    StringBuilder formats = new StringBuilder();
    
    // Add including formats
    if (includingFormats != null) {
      formats.append(String.join(",", includingFormats));
    }
    
    // Add excluding formats with ! prefix
    if (excludingFormats != null) {
      if (formats.length() > 0 && !excludingFormats.isEmpty()) {
        formats.append(",");
      }
      String excludes = excludingFormats.stream()
          .map(format -> STR."!\{format}")
          .collect(joining(","));
      formats.append(excludes);
    }
    
    return formats.toString();
  }

  /**
   * Gets the version policies filter string using stream operations and String Templates.
   * 
   * @return The version policies filter string
   * @since 3.60
   */
  private String getVersionPolicies() {
    return concat(
        ofNullable(includingVersionPolicies).orElse(emptyList()).stream(),
        ofNullable(excludingVersionPolicies).orElse(emptyList()).stream()
            .map(policy -> STR."!\{policy}")
        ).collect(joining(","));
  }

  /**
   * Asynchronously fetches repository data using virtual threads for improved performance.
   * This method demonstrates the use of virtual threads for I/O-bound operations.
   *
   * @param repositoryIds List of repository IDs to fetch
   * @return CompletableFuture with a list of RepositoryInfo objects
   * @since 3.60
   */
  public CompletableFuture<List<RepositoryInfo>> fetchRepositoryDataAsync(List<String> repositoryIds) {
    return CompletableFuture.supplyAsync(() -> {
      // This would typically involve I/O operations like database or network calls
      // Using virtual threads for such operations provides better scalability
      return repositoryIds.stream()
          .map(id -> new RepositoryInfo(id, "Repository " + id))
          .toList();
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Processes repository information using pattern matching for improved type safety.
   * This method demonstrates the use of record patterns for destructuring repository data.
   *
   * @param repositoryInfo The repository information to process
   * @return A formatted string with repository details
   * @since 3.60
   */
  public String processRepositoryInfo(Object repositoryInfo) {
    return switch (repositoryInfo) {
      case RepositoryInfo(String id, String name) when id.startsWith("hosted-") -> 
          STR."Hosted Repository: \{name} (\{id})";
      case RepositoryInfo(String id, String name) when id.startsWith("proxy-") -> 
          STR."Proxy Repository: \{name} (\{id})";
      case RepositoryInfo(String id, String name) when id.startsWith("group-") -> 
          STR."Group Repository: \{name} (\{id})";
      case RepositoryInfo(String id, String name) -> 
          STR."Repository: \{name} (\{id})";
      default -> "Unknown Repository";
    };
  }
}