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
package org.sonatype.nexus.repository.search.normalize;

/**
 * Format specific version normalizer for repository content.
 * <p>
 * This interface defines the contract for normalizing version strings across different repository formats.
 * Implementations should provide format-specific logic to convert version strings into a normalized form
 * that can be used for consistent sorting, comparison, and search operations.
 * <p>
 * With Java 21 compatibility, implementations can leverage the following features for improved performance
 * and code quality:
 * <ul>
 *   <li>Virtual Threads for concurrent normalization operations when processing large datasets</li>
 *   <li>Pattern Matching for switch statements when handling different version formats or components</li>
 *   <li>String Templates for improved logging or error messages during normalization</li>
 *   <li>Record Patterns for handling structured version data in a type-safe manner</li>
 * </ul>
 * <p>
 * While this interface remains backward compatible, implementations may utilize Java 21 features
 * internally to optimize performance and maintainability.
 */
public interface VersionNormalizer
{
  /**
   * Return the version in normalized form for specific format.
   * <p>
   * The normalized version should maintain semantic ordering when compared lexicographically.
   * For example, "1.10.0" should sort after "1.2.0" when normalized.
   * <p>
   * Implementations should handle edge cases such as:
   * <ul>
   *   <li>null or empty version strings</li>
   *   <li>non-standard version formats</li>
   *   <li>qualifiers and build metadata</li>
   * </ul>
   * <p>
   * With Java 21, implementations can use pattern matching for more elegant version parsing
   * and string templates for efficient error reporting.
   *
   * @param version the original version string to normalize (may be null)
   * @return the normalized version string, or null if the input was null
   */
  String getNormalizedVersion(final String version);
}