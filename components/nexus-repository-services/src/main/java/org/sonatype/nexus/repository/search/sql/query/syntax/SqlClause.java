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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SequencedCollection;

import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An SQL clause which uses the same operand for a collection of predicates, e.g. {@code foo = 'bar' AND bar = 'foo'}
 */
public class SqlClause
    implements Expression
{
  private final Operand operand;

  private final List<Expression> expressions;

  /*
   * private constructor to allow us to optimize in the create method
   */
  private SqlClause(final Operand operand, final List<? extends Expression> expressions) {
    this.operand = checkNotNull(operand);
    checkArgument(operand == Operand.AND || operand == Operand.OR, "Unexpected operand: " + operand.toString());
    checkArgument(expressions.size() > 1, "Must have at least 2 expressions");
    this.expressions = ImmutableList.copyOf(checkNotNull(expressions));
  }

  /**
   * The expressions in this clause which are conjoined by the operand
   */
  public List<Expression> expressions() {
    return Collections.unmodifiableList(expressions);
  }

  @Override
  public Operand operand() {
    return operand;
  }

  @Override
  public int hashCode() {
    return Objects.hash(operand, expressions);
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    SqlClause other = (SqlClause) obj;

    return operand == other.operand && Objects.equals(expressions,  other.expressions);
  }

  @Override
  public String toString() {
    return STR."(\{expressions.stream()
        .map(Object::toString)
        .collect(java.util.stream.Collectors.joining(' ' + operand.toString() + ' '))})"; 
  }

  public static Expression create(final Operand operand, final Expression... expressions) {
    return switch (expressions.length) {
      case 0 -> throw new IllegalArgumentException("Must have at least 1 expression");
      case 1 -> expressions[0]; // optimization: avoid creating the clause and return the only expression provided
      default -> new SqlClause(operand, Arrays.asList(expressions));
    };
  }

  public static Expression create(final Operand operand, final List<? extends Expression> expressions) {
    return switch (expressions) {
      case List<?> list when list.isEmpty() -> throw new IllegalArgumentException("Must have at least 1 expression");
      case SequencedCollection<?> seq when seq.size() == 1 -> seq.getFirst(); // optimization using SequencedCollection
      case List<?> list when list.size() == 1 -> list.get(0); // fallback for non-sequenced lists
      default -> new SqlClause(operand, expressions);
    };
  }
}