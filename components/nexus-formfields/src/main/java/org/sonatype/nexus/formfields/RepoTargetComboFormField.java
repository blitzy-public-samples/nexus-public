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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static java.lang.StringTemplate.STR;

/**
 * The model for a combo field allowing for selection of Repository Targets.
 *
 * @since 2.5
 */
public class RepoTargetComboFormField
    extends Combobox<String>
{
  /**
   * Record for repository target information with id and name.
   * Used for type-safe handling of repository target data.
   *
   * @since 3.60
   */
  public record RepositoryTargetInfo(String id, String name) {}

  public static final String DEFAULT_HELP_TEXT = "Select the repository target to apply ";

  public static final String DEFAULT_LABEL = "Repository Target";

  public RepoTargetComboFormField(String id,
                                  String label,
                                  String helpText,
                                  boolean required,
                                  String regexValidation)
  {
    super(id, label, helpText, required, regexValidation);
  }

  public RepoTargetComboFormField(String id, String label, String helpText, boolean required) {
    super(id, label, helpText, required);
  }

  public RepoTargetComboFormField(String id, boolean required) {
    super(id, DEFAULT_LABEL, DEFAULT_HELP_TEXT, required);
  }

  public RepoTargetComboFormField(String id) {
    super(id, DEFAULT_LABEL, DEFAULT_HELP_TEXT, false);
  }

  public String getType() {
    return "repo-target";
  }

  /**
   * @since 3.0
   */
  @Override
  public String getStoreApi() {
    return "coreui_RepositoryTarget.read";
  }

  /**
   * @since 3.0
   */
  @Override
  public Map<String, String> getStoreFilters() {
    return null;
  }
  
  /**
   * Asynchronously fetches repository target data using virtual threads for improved performance.
   * This method demonstrates the use of virtual threads for I/O-bound operations like API calls.
   *
   * @param targetIds List of repository target IDs to fetch
   * @return CompletableFuture with a list of RepositoryTargetInfo objects
   * @since 3.60
   */
  public CompletableFuture<List<RepositoryTargetInfo>> fetchRepositoryTargetDataAsync(List<String> targetIds) {
    return CompletableFuture.supplyAsync(() -> {
      // This would typically involve I/O operations like API calls to coreui_RepositoryTarget.read
      // Using virtual threads for such operations provides better scalability
      return targetIds.stream()
          .map(id -> new RepositoryTargetInfo(id, "Target " + id))
          .toList();
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Processes repository target information using pattern matching for improved type safety.
   * This method demonstrates the use of record patterns for destructuring repository target data.
   *
   * @param targetInfo The repository target information to process
   * @return A formatted string with repository target details
   * @since 3.60
   */
  public String processRepositoryTargetInfo(Object targetInfo) {
    return switch (targetInfo) {
      case RepositoryTargetInfo(String id, String name) when id.startsWith("maven-") -> 
          STR."Maven Repository Target: \{name} (\{id})";
      case RepositoryTargetInfo(String id, String name) when id.startsWith("npm-") -> 
          STR."NPM Repository Target: \{name} (\{id})";
      case RepositoryTargetInfo(String id, String name) when id.startsWith("nuget-") -> 
          STR."NuGet Repository Target: \{name} (\{id})";
      case RepositoryTargetInfo(String id, String name) -> 
          STR."Repository Target: \{name} (\{id})";
      default -> "Unknown Repository Target";
    };
  }
  
  /**
   * Validates a repository target ID and returns an error message if invalid.
   * This method demonstrates the use of String Templates for error messaging.
   *
   * @param targetId The repository target ID to validate
   * @return Error message if invalid, null if valid
   * @since 3.60
   */
  public String validateRepositoryTargetId(String targetId) {
    if (targetId == null || targetId.isEmpty()) {
      return STR."Repository target ID cannot be empty";
    }
    
    if (targetId.length() < 3) {
      return STR."Repository target ID '\{targetId}' is too short (minimum 3 characters)";
    }
    
    if (targetId.contains(" ")) {
      return STR."Repository target ID '\{targetId}' cannot contain spaces";
    }
    
    return null; // Valid
  }
}
