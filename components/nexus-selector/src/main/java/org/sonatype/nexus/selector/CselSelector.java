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
 *
 * @since 3.6
 */
public class CselSelector
    extends JexlSelector
{
  public static final String TYPE = "csel";

  private final CselToSql cselToSql;

  public CselSelector(final CselToSql cselToSql, final JexlExpression expression) {
    super(expression);
    this.cselToSql = checkNotNull(cselToSql);
  }

  @Override
  public void toSql(final SelectorSqlBuilder sqlBuilder) {
    // Get the syntax tree and transform it to SQL using pattern matching
    ASTJexlScript syntaxTree = expression.getSyntaxTree();
    if (syntaxTree != null) {
      cselToSql.transformCselToSql(syntaxTree, sqlBuilder);
    }
  }

  @Override
  public <T> void toSql(final T sqlBuilder, final CselToSql<T> cselToSqlTransformer) {
    // Use pattern matching to handle the transformation
    switch (sqlBuilder) {
      case SelectorSqlBuilder builder when cselToSqlTransformer != null -> 
          cselToSqlTransformer.transformCselToSql(expression.getSyntaxTree(), sqlBuilder);
      case null -> throw new IllegalArgumentException("SQL builder cannot be null");
      default -> cselToSqlTransformer.transformCselToSql(expression.getSyntaxTree(), sqlBuilder);
    }
  }
}