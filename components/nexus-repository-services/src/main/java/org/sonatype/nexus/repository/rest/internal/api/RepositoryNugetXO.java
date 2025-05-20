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
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Repository transfer object for Nuget repositories.
 * Implemented as a Java Record for improved immutability.
 *
 * @since 3.30
 */
public record RepositoryNugetXO(
    String name,
    String type,
    String format,
    String url,
    RepositoryStatusXO status,
    @Nullable String nugetVersion,
    @Nullable Collection<String> memberNames)
{
  /**
   * Constructor with validation for required fields.
   */
  public RepositoryNugetXO {
    checkNotNull(name);
    checkNotNull(type);
    checkNotNull(format);
    checkNotNull(url);
    checkNotNull(status);
    // nugetVersion and memberNames can be null
  }
  
  /**
   * Constructor that creates a RepositoryNugetXO from a RepositoryDetailXO and additional Nuget-specific fields.
   */
  public RepositoryNugetXO(RepositoryDetailXO detail, @Nullable String nugetVersion, @Nullable Collection<String> memberNames) {
    this(detail.getName(), detail.getType(), detail.getFormat(), detail.getUrl(), detail.getStatus(), 
        nugetVersion, memberNames);
  }

  /**
   * Returns the Nuget version.
   * Uses pattern matching to handle null values.
   *
   * @return the Nuget version or null if not available
   */
  @Nullable
  public String getNugetVersion() {
    return switch(this) {
      case RepositoryNugetXO(_, _, _, _, _, String version, _) -> version;
      default -> null;
    };
  }

  /**
   * Returns the member names collection.
   * Uses pattern matching to handle null values.
   *
   * @return the collection of member names or null if not available
   */
  @Nullable
  public Collection<String> getMemberNames() {
    return switch(this) {
      case RepositoryNugetXO(_, _, _, _, _, _, Collection<String> members) -> members;
      default -> null;
    };
  }
}
