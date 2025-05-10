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
package org.sonatype.nexus.repository.search.sql.query.syntax;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An operand for use in queries.
 */
public enum Operand
{
  /**
   * An operand indicating that the term should be or'd together
   */
  OR(true),

  /**
   * An operand indicating that the term should be and'd together
   */
  AND(true),

  /**
   * An operand indicating that the term should be equal
   */
  EQ(true),

  /**
   * An operand indicating that the term should not be equal
   */
  NOT_EQ(false),

  /**
   * An operand indicating a regular expression match
   */
  REGEX(false),

  /**
   * An operand indicating that one of the terms should match.
   */
  IN(true),

  ANY(true);

  private final boolean multiple;

  Operand(final boolean multiple) {
    this.multiple = multiple;
  }

  /**
   * Indicates whether the operand supports multiple terms
   *
   * @return true if the operand supports multiple terms, false otherwise
   */
  public boolean supportsMultiple() {
    return multiple;
  }

  /**
   * Returns a string representation of this operand using String Templates.
   *
   * @return a string representation of this operand
   */
  @Override
  public String toString() {
    return STR."Operand[\{name()}, supportsMultiple=\{multiple}]";
  }

  /**
   * Finds an operand by name, case-insensitive.
   *
   * @param name the name to search for
   * @return an Optional containing the operand if found, or empty if not found
   */
  public static Optional<Operand> findByName(final String name) {
    return Arrays.stream(values())
        .filter(op -> op.name().equalsIgnoreCase(name))
        .findFirst();
  }

  /**
   * Finds all operands that match the given predicate.
   *
   * @param predicate the predicate to match against
   * @return a list of matching operands
   */
  public static List<Operand> findAll(final Predicate<Operand> predicate) {
    return Arrays.stream(values())
        .filter(predicate)
        .toList();
  }

  /**
   * Returns all operands that support multiple terms.
   *
   * @return a list of operands that support multiple terms
   */
  public static List<Operand> allMultipleTerms() {
    return findAll(Operand::supportsMultiple);
  }

  /**
   * Returns all operands that do not support multiple terms.
   *
   * @return a list of operands that do not support multiple terms
   */
  public static List<Operand> allSingleTerms() {
    return findAll(op -> !op.supportsMultiple());
  }
}