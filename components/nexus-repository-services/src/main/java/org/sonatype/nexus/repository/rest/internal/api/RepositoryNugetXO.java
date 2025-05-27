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
package org.sonatype.nexus.repository.rest.internal.api;

import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;

/**
 * Repository NuGet data transfer object implemented as a Java Record.
 * Extends RepositoryDetailXO and adds NuGet-specific fields.
 *
 * @since 3.30
 */
public record RepositoryNugetXO(
    @Nullable String nugetVersion,
    @Nullable Collection<String> memberNames
) implements RepositoryDetailXOExtension
{
  /**
   * Creates a new RepositoryNugetXO with the specified parameters.
   *
   * @param name         the repository name
   * @param type         the repository type
   * @param format       the repository format
   * @param url          the repository URL
   * @param status       the repository status
   * @param nugetVersion the NuGet version
   * @param memberNames  the collection of member repository names
   */
  public RepositoryNugetXO(
      final String name,
      final String type,
      final String format,
      final String url,
      final RepositoryStatusXO status,
      @Nullable final String nugetVersion,
      @Nullable final Collection<String> memberNames)
  {
    this(nugetVersion, memberNames);
    this.repositoryDetailXO = new RepositoryDetailXO(name, type, format, url, status);
  }
  
  /**
   * Compact constructor to validate and normalize the record components.
   */
  public RepositoryNugetXO {
    // Use pattern matching to handle nullable fields
    memberNames = switch (memberNames) {
      case null -> Collections.emptyList();
      default -> Collections.unmodifiableCollection(memberNames);
    };
  }
  
  /**
   * The base RepositoryDetailXO instance.
   */
  private final transient RepositoryDetailXO repositoryDetailXO;
  
  /**
   * Gets the repository name.
   *
   * @return the repository name
   */
  @Override
  public String getName() {
    return repositoryDetailXO.getName();
  }
  
  /**
   * Gets the repository type.
   *
   * @return the repository type
   */
  @Override
  public String getType() {
    return repositoryDetailXO.getType();
  }
  
  /**
   * Gets the repository format.
   *
   * @return the repository format
   */
  @Override
  public String getFormat() {
    return repositoryDetailXO.getFormat();
  }
  
  /**
   * Gets the repository URL.
   *
   * @return the repository URL
   */
  @Override
  public String getUrl() {
    return repositoryDetailXO.getUrl();
  }
  
  /**
   * Gets the repository status.
   *
   * @return the repository status
   */
  @Override
  public RepositoryStatusXO getStatus() {
    return repositoryDetailXO.getStatus();
  }
  
  /**
   * Utility method to extract NuGet version and member names from a repository object using pattern matching.
   *
   * @param repository the repository object to extract data from
   * @return a new RepositoryNugetXO if the repository is a NuGet repository, null otherwise
   */
  public static @Nullable RepositoryNugetXO fromRepository(Object repository) {
    return switch (repository) {
      case RepositoryNugetXO(var version, var members) -> new RepositoryNugetXO(version, members);
      default -> null;
    };
  }
  
  /**
   * Utility method to check if a repository has a specific NuGet version using pattern matching.
   *
   * @param repository the repository object to check
   * @param version the NuGet version to check for
   * @return true if the repository is a NuGet repository with the specified version, false otherwise
   */
  public static boolean hasNugetVersion(Object repository, String version) {
    return repository instanceof RepositoryNugetXO(String nugetVersion, var _) && 
           version.equals(nugetVersion);
  }
  
  /**
   * Utility method to check if a repository contains a specific member using pattern matching.
   *
   * @param repository the repository object to check
   * @param memberName the member name to check for
   * @return true if the repository is a NuGet repository containing the specified member, false otherwise
   */
  public static boolean containsMember(Object repository, String memberName) {
    return repository instanceof RepositoryNugetXO(var _, Collection<String> members) && 
           members != null && members.contains(memberName);
  }
}