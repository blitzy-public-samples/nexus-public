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

import java.util.Objects;

import org.sonatype.nexus.repository.rest.sql.SearchField;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A predicate used in an SQL query, e.g. {@code foo = 'bar'}
 * 
 * @since 3.60
 */
public class SqlPredicate
    implements Expression
{
  /**
   * Record to represent the field-operand-term combination for more efficient pattern matching.
   * This enables direct access to components through record patterns.
   */
  private record PredicateComponents(Operand operand, SearchField searchField, Term term) {}

  private final PredicateComponents components;

  /**
   * Constructs a new SQL predicate with the specified operand, search field, and term.
   *
   * @param operand the operand to use in this predicate (must not be null)
   * @param searchField the search field to use in this predicate (must not be null)
   * @param term the term to use in this predicate (must not be null)
   */
  public SqlPredicate(final Operand operand, final SearchField searchField, final Term term) {
    checkNotNull(operand, "Operand cannot be null");
    checkNotNull(searchField, "SearchField cannot be null");
    checkNotNull(term, "Term cannot be null");
    this.components = new PredicateComponents(operand, searchField, term);
  }

  @Override
  public Operand operand() {
    return components.operand();
  }

  /**
   * The database field on the left of the operand in this predicate.
   *
   * @return the search field used in this predicate
   */
  public SearchField getSearchField() {
    return components.searchField();
  }

  /**
   * The term on the right of the operand in this predicate.
   *
   * @return the term used in this predicate
   */
  public Term getTerm() {
    return components.term();
  }

  /**
   * Extracts the components of this predicate using record pattern matching.
   * This demonstrates the use of record patterns for efficient component access.
   *
   * @return the components of this predicate as a string
   */
  public String getComponentsAsString() {
    // Using record pattern to extract components in a single step
    if (components instanceof PredicateComponents(var op, var field, var t)) {
      return STR."Operand: \{op}, Field: \{field}, Term: \{t}";
    }
    return "Invalid components";
  }

  @Override
  public int hashCode() {
    // Using record pattern to extract components for hash code calculation
    if (components instanceof PredicateComponents(var op, var field, var t)) {
      return Objects.hash(op, field, t);
    }
    return 0;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    
    // Using pattern matching for instanceof to simplify type checking and casting
    if (obj instanceof SqlPredicate other) {
      // Using record patterns to extract components from both objects for comparison
      if (components instanceof PredicateComponents(var thisOp, var thisField, var thisTerm) &&
          other.components instanceof PredicateComponents(var otherOp, var otherField, var otherTerm)) {
        return thisOp == otherOp && 
               thisField == otherField && 
               Objects.equals(thisTerm, otherTerm);
      }
    }
    return false;
  }

  @Override
  public String toString() {
    // Using String Templates with record pattern for improved debugging output
    if (components instanceof PredicateComponents(var op, var field, var term)) {
      return STR."SqlPredicate [searchField=\{field}, operand=\{op}, term=\{term}]";
    }
    return "SqlPredicate [invalid]";
  }
}