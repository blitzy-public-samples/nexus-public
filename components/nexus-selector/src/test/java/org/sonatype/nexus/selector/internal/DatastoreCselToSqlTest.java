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
package org.sonatype.nexus.selector.internal;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;
import org.sonatype.nexus.selector.JexlEngine;
import org.sonatype.nexus.selector.SelectorSqlBuilder;

import org.apache.commons.jexl3.parser.ASTJexlScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DatastoreCselToSqlTest
    extends TestSupport
{
  private JexlEngine jexlEngine = new JexlEngine();

  private SelectorSqlBuilder builder;

  private DatastoreCselToSql underTest;

  @BeforeEach
  public void setup() {
    underTest = new DatastoreCselToSql();
  }

  @BeforeEach
  public void createSqlBuilder() {
    builder = new SelectorSqlBuilder();

    builder.propertyAlias("a", "a_alias");
    builder.propertyAlias("b", "b_alias");

    builder.propertyPrefix("prop.");
    builder.parameterPrefix(":");
    builder.parameterNamePrefix("param_");
  }

  @Test
  public void and() {
    final ASTJexlScript script = jexlEngine.parseExpression("a==\"woof\" && b==\"meow\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias = :param_0 and b_alias = :param_1"));
    assertThat(builder.getQueryParameters().size(), is(2));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
    assertThat(builder.getQueryParameters().get("param_1"), is("meow"));
  }

  @Test
  public void or() {
    final ASTJexlScript script = jexlEngine.parseExpression("a==\"woof\" || b==\"meow\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias = :param_0 or b_alias = :param_1"));
    assertThat(builder.getQueryParameters().size(), is(2));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
    assertThat(builder.getQueryParameters().get("param_1"), is("meow"));
  }

  @Test
  public void like() {
    final ASTJexlScript script = jexlEngine.parseExpression("a =^ \"woof\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias like :param_0"));
    assertThat(builder.getQueryParameters().size(), is(1));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof%"));
  }

  @Test
  public void notEqual() {
    final ASTJexlScript script = jexlEngine.parseExpression("a != \"woof\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("(a_alias is null or a_alias <> :param_0)"));
    assertThat(builder.getQueryParameters().size(), is(1));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
  }

  @Test
  public void parens() {
    final ASTJexlScript script = jexlEngine.parseExpression("a==\"woof\" && (b==\"meow\" || b==\"purr\")");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias = :param_0 and (b_alias = :param_1 or b_alias = :param_2)"));
    assertThat(builder.getQueryParameters().size(), is(3));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
    assertThat(builder.getQueryParameters().get("param_1"), is("meow"));
    assertThat(builder.getQueryParameters().get("param_2"), is("purr"));
  }

  @Test
  public void ref() {
    final ASTJexlScript script = jexlEngine.parseExpression("a == dog.name");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias = prop.name"));
    assertThat(builder.getQueryParameters().size(), is(0));
  }

  @Test
  public void regexp() {
    ASTJexlScript script = jexlEngine.parseExpression("a =~ \"woof\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryString(), is("a_alias ~ :param_0"));
    assertThat(builder.getQueryParameters().size(), is(1));
    assertThat(builder.getQueryParameters().get("param_0"), is("^(woof)$"));

    reset();

    script = jexlEngine.parseExpression("a =~ \"woof$\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryParameters().get("param_0"), is("^(woof$)$"));

    reset();

    script = jexlEngine.parseExpression("a =~ \"^woof\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryParameters().get("param_0"), is("^woof"));

    reset();

    script = jexlEngine.parseExpression("a =~ \"^/woof|/woof/foo\"");

    script.childrenAccept(underTest, builder);

    assertThat(builder.getQueryParameters().get("param_0"), is("^/woof|/woof/foo"));
  }

  @Test
  public void recordPatternMatchingGeneratesCorrectSql() {
    // Define a simple record for testing pattern matching
    record QueryCondition(String field, String operator, String value) {}
    
    // Create a condition using the record
    QueryCondition condition = new QueryCondition("a", "=", "woof");
    
    // Use pattern matching to extract components
    if (condition instanceof QueryCondition(String field, String operator, String value)) {
      // Build expression based on extracted components
      final ASTJexlScript script = jexlEngine.parseExpression(field + operator + "\"" + value + "\"");
      
      script.childrenAccept(underTest, builder);
      
      assertThat(builder.getQueryString(), is("a_alias = :param_0"));
      assertThat(builder.getQueryParameters().size(), is(1));
      assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
    }
  }

  @Test
  public void stringTemplateGeneratesCorrectSql() {
    // Define variables for the template
    String field = "a";
    String value = "woof";
    
    // Use String Template to create the expression
    String expression = STR."\{field}==\"\{value}\"";
    
    final ASTJexlScript script = jexlEngine.parseExpression(expression);
    
    script.childrenAccept(underTest, builder);
    
    assertThat(builder.getQueryString(), is("a_alias = :param_0"));
    assertThat(builder.getQueryParameters().size(), is(1));
    assertThat(builder.getQueryParameters().get("param_0"), is("woof"));
  }

  private void reset() {
    builder.clearQueryString();
    builder.getQueryParameters().clear();
  }
}