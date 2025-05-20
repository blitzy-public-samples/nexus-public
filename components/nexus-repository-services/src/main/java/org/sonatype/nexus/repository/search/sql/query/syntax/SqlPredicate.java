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
import static java.lang.StringTemplate.STR;

/**
 * A predicate used in an SQL query, e.g. {@code foo = 'bar'}
 */
public class SqlPredicate
    implements Expression
{
  private final Operand operand;

  private final SearchField searchField;

  private final Term term;
  
  /**
   * Record representation of SqlPredicate for use with record patterns.
   * Enables efficient destructuring of predicate components.
   *
   * @since 3.60
   */
  public record PredicateComponents(Operand operand, SearchField searchField, Term term) {}

  public SqlPredicate(final Operand operand, final SearchField searchField, final Term term) {
    this.operand = checkNotNull(operand);
    this.searchField = checkNotNull(searchField);
    this.term = checkNotNull(term);
  }

  @Override
  public Operand operand() {
    return operand;
  }

  /**
   * The database field on the left of the operand in this predicate.
   */
  public SearchField getSearchField() {
    return searchField;
  }

  /**
   * The term on the right of the operand in this predicate.
   */
  public Term getTerm() {
    return term;
  }
  
  /**
   * Returns the components of this predicate as a record for use with record patterns.
   * This enables more efficient field-operand-term handling through pattern matching.
   *
   * @return record containing the predicate components
   * @since 3.60
   */
  public PredicateComponents components() {
    return new PredicateComponents(operand, searchField, term);
  }
  
  /**
   * Processes this predicate using record pattern matching for more efficient component access.
   * Demonstrates the use of record patterns for field-operand-term handling.
   *
   * @param processor function to process the predicate components
   * @param <R> the return type
   * @return the result of processing
   * @since 3.60
   */
  public <R> R processWithPatternMatching(java.util.function.Function<PredicateComponents, R> processor) {
    return processor.apply(components());
  }

  @Override
  public int hashCode() {
    return Objects.hash(operand, searchField, term);
  }

  @Override
  public boolean equals(final Object obj) {
    // Using pattern matching for instanceof check to improve type handling safety
    return this == obj || (obj instanceof SqlPredicate other && 
        operand == other.operand && 
        searchField == other.searchField && 
        Objects.equals(term, other.term));
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved debugging output
    return STR."SqlPredicate[searchField=\{searchField}, operand=\{operand}, term=\{term}]";
  }
}
