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

/**
 * Represents a truthy expression for use in SQL such as {@code WHERE expression}.
 * <p>
 * This interface defines the contract for SQL expressions that can be used in query conditions.
 * Implementations include {@link SqlPredicate} for simple conditions and {@link SqlClause} for
 * compound conditions joined by logical operators.
 * <p>
 * Expressions can be used with pattern matching in Java 21+ for more concise and type-safe code:
 * <pre>
 * {@code
 * String formatExpression(Expression expr) {
 *   return switch(expr) {
 *     case SqlPredicate p -> formatPredicate(p);
 *     case SqlClause c -> formatClause(c);
 *     default -> expr.toString();
 *   };
 * }
 * }
 * </pre>
 */
public interface Expression
{
  /**
   * Returns the operand used by this expression.
   * <p>
   * The operand defines the logical operation represented by this expression,
   * such as {@link Operand#AND}, {@link Operand#OR}, or comparison operators
   * like {@link Operand#EQ}.
   *
   * @return the operand for this expression, never {@code null}
   */
  Operand operand();
  
  /**
   * Creates a new AND clause combining this expression with the given expression.
   * <p>
   * This is a convenience method for creating compound expressions.
   *
   * @param other the expression to AND with this one
   * @return a new expression representing this AND other
   * @since 21.0
   */
  default Expression and(Expression other) {
    return SqlClause.create(Operand.AND, this, other);
  }
  
  /**
   * Creates a new OR clause combining this expression with the given expression.
   * <p>
   * This is a convenience method for creating compound expressions.
   *
   * @param other the expression to OR with this one
   * @return a new expression representing this OR other
   * @since 21.0
   */
  default Expression or(Expression other) {
    return SqlClause.create(Operand.OR, this, other);
  }
}