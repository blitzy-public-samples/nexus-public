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
package org.sonatype.nexus.repository.maven;

import javax.annotation.Nonnull;

/**
 * Component representing Maven layout specific bits, like parsing request paths into {@link MavenPath}.
 * <p>
 * Implementations of this interface should leverage Java 21 features where appropriate, particularly
 * for performance-critical path parsing operations. Consider using pattern matching for type checks
 * and string templates for complex path manipulations in implementations.
 *
 * @since 3.0
 */
public interface MavenPathParser
{
  /**
   * Parses path into {@link MavenPath}.
   * <p>
   * Implementations should be optimized for Java 21 runtime environment.
   */
  @Nonnull
  MavenPath parsePath(String path);

  /**
   * Parses path into {@link MavenPath} with optional case sensitivity
   * <p>
   * Implementations should be optimized for Java 21 runtime environment.
   *
   * @since 3.7
   */
  @Nonnull
  MavenPath parsePath(String path, boolean caseSensitive);

  /**
   * Returns {@code true} if passed in path represent repository metadata path.
   * <p>
   * Implementations may leverage Java 21 pattern matching for improved performance.
   */
  boolean isRepositoryMetadata(MavenPath path);

  /**
   * Returns {@code true} if passed in path represents a repository index file path.
   * <p>
   * Implementations may leverage Java 21 pattern matching for improved performance.
   */
  boolean isRepositoryIndex(MavenPath path);
}