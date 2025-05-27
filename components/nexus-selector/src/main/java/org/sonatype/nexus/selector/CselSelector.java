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
package org.sonatype.nexus.selector;

import org.apache.commons.jexl3.parser.ASTJexlScript;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Subset of JEXL selectors that can also be represented as SQL.
 * Updated for Java 21 with pattern matching and enhanced encapsulation.
 *
 * @since 3.6
 */
public class CselSelector
    extends JexlSelector
{
  public static final String TYPE = "csel";

  private final CselToSql cselToSql;

  /**
   * Constructs a new CSEL selector with the specified SQL transformer and expression.
   *
   * @param cselToSql the SQL transformer to use (must not be null)
   * @param expression the JEXL expression to evaluate (must not be null)
   */
  public CselSelector(final CselToSql cselToSql, final JexlExpression expression) {
    super(expression);
    this.cselToSql = checkNotNull(cselToSql);
  }

  /**
   * Transforms this selector's CSEL expression into SQL using the configured transformer.
   *
   * @param sqlBuilder the builder to populate with SQL fragments
   */
  @Override
  public void toSql(final SelectorSqlBuilder sqlBuilder) {
    // Using pattern matching to ensure we have a valid syntax tree
    var syntaxTree = switch (expression) {
      case JexlExpression expr when expr.getSyntaxTree() != null -> expr.getSyntaxTree();
      case JexlExpression expr -> throw new IllegalStateException("Invalid syntax tree in expression: " + expr);
    };
    
    cselToSql.transformCselToSql(syntaxTree, sqlBuilder);
  }

  /**
   * Transforms this selector's CSEL expression into SQL using the provided transformer.
   *
   * @param sqlBuilder the builder to populate with SQL fragments
   * @param cselToSql the SQL transformer to use
   * @param <T> the type of SQL builder
   */
  @Override
  public <T> void toSql(final T sqlBuilder, final CselToSql<T> cselToSql) {
    checkNotNull(cselToSql, "CselToSql transformer cannot be null");
    checkNotNull(sqlBuilder, "SQL builder cannot be null");
    
    // Using pattern matching to ensure we have a valid syntax tree
    var syntaxTree = switch (expression) {
      case JexlExpression expr when expr.getSyntaxTree() != null -> expr.getSyntaxTree();
      case JexlExpression expr -> throw new IllegalStateException("Invalid syntax tree in expression: " + expr);
    };
    
    cselToSql.transformCselToSql(syntaxTree, sqlBuilder);
  }
}