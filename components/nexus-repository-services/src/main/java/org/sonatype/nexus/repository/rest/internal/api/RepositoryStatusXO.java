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

import javax.annotation.Nullable;

/**
 * @since 3.30
 */
public record RepositoryStatusXO(boolean online,
                                @Nullable String description,
                                @Nullable String reason) {

  /**
   * Returns whether the repository is online.
   *
   * @return true if the repository is online, false otherwise
   */
  public boolean isOnline() {
    return online;
  }

  /**
   * Returns the description of the repository status.
   * Uses pattern matching to handle null values.
   *
   * @return the description or null if not available
   */
  @Nullable
  public String getDescription() {
    return switch(this) {
      case RepositoryStatusXO(_, String desc, _) -> desc;
      default -> null;
    };
  }

  /**
   * Returns the reason for the repository status.
   * Uses pattern matching to handle null values.
   *
   * @return the reason or null if not available
   */
  @Nullable
  public String getReason() {
    return switch(this) {
      case RepositoryStatusXO(_, _, String rsn) -> rsn;
      default -> null;
    };
  }
}