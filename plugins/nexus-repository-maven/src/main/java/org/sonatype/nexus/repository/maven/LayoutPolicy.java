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

/**
 * Layout policy for Maven repositories.
 * 
 * This enum is compatible with Java 21 pattern matching for switch expressions and statements.
 * Example usage with Java 21 pattern matching:
 * <pre>
 * {@code
 * String description = switch(layoutPolicy) {
 *   case STRICT -> "Standard Maven 2 layout only";
 *   case PERMISSIVE -> "Any repository path allowed";
 * };
 * }
 * </pre>
 * 
 * @since 3.0
 * @see <a href="https://openjdk.org/jeps/441">JEP 441: Pattern Matching for switch</a>
 */
public enum LayoutPolicy
{
  /**
   * Only allow repository paths that are Maven 2 standard layout compliant.
   * This enforces the standard Maven directory structure and naming conventions.
   */
  STRICT,

  /**
   * Allow any repository paths without enforcing Maven layout standards.
   * This provides flexibility for non-standard Maven repository structures.
   */
  PERMISSIVE
}