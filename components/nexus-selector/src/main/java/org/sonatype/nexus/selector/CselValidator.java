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

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.parser.ASTAndNode;
import org.apache.commons.jexl3.parser.ASTEQNode;
import org.apache.commons.jexl3.parser.ASTERNode;
import org.apache.commons.jexl3.parser.ASTIdentifier;
import org.apache.commons.jexl3.parser.ASTJexlScript;
import org.apache.commons.jexl3.parser.ASTNENode;
import org.apache.commons.jexl3.parser.ASTOrNode;
import org.apache.commons.jexl3.parser.ASTReferenceExpression;
import org.apache.commons.jexl3.parser.ASTSWNode;
import org.apache.commons.jexl3.parser.ASTStringLiteral;
import org.apache.commons.jexl3.parser.JexlNode;

import static java.lang.String.format;

/**
 * Walks the script, checking whether it represents a valid CSEL expression.
 *
 * @since 3.16
 */
class CselValidator
    extends ParserVisitorSupport
{
  private static final CselValidator INSTANCE = new CselValidator();

  private static final Set<String> VALID_IDENTIFIERS = ImmutableSet.of("format", "path");

  private static final String EMBEDDED_STRING_MESSAGE = "String literal '%s' should not contain embedded string (\" or \')";

  private static final String BAD_IDENTIFIER_MESSAGE = "Invalid identifier %s, expected one of " + VALID_IDENTIFIERS;

  /**
   * Validates the given CSEL expression (in script form).
   *
   * @param script the CSEL script to validate
   */
  public static void validateCselExpression(final ASTJexlScript script) {
    script.childrenAccept(INSTANCE, null);
  }

  private CselValidator() {
    // utility class
  }

  /**
   * Uses pattern matching for switch to validate different AST node types.
   * Only specific node types are allowed in CSEL expressions.
   *
   * @param node the node to visit
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   */
  @Override
  protected Object doVisit(final JexlNode node, final Object data) {
    return switch (node) {
      // Logical operators
      case ASTOrNode orNode -> orNode.childrenAccept(this, data);
      case ASTAndNode andNode -> andNode.childrenAccept(this, data);
      
      // Comparison operators
      case ASTEQNode eqNode -> eqNode.childrenAccept(this, data);
      case ASTNENode neNode -> neNode.childrenAccept(this, data);
      
      // Regex matching
      case ASTERNode erNode -> {
        try {
          Pattern.compile(erNode.jjtGetChild(1).toString());
          yield erNode.childrenAccept(this, data);
        }
        catch (PatternSyntaxException e) {
          throw new JexlException(erNode, e.getDescription());
        }
      }
      
      // Starts with operator
      case ASTSWNode swNode -> swNode.childrenAccept(this, data);
      
      // Parenthesized expressions
      case ASTReferenceExpression refExpr -> refExpr.childrenAccept(this, data);
      
      // String literals
      case ASTStringLiteral strLiteral -> {
        String literal = strLiteral.getLiteral();
        if (!literal.contains("\"") && !literal.contains("'")) {
          yield strLiteral.childrenAccept(this, data);
        }
        else {
          throw new JexlException(strLiteral, format(EMBEDDED_STRING_MESSAGE, literal));
        }
      }
      
      // Identifiers
      case ASTIdentifier identifier -> {
        String id = identifier.getName();
        if (VALID_IDENTIFIERS.contains(id)) {
          yield identifier.childrenAccept(this, data);
        }
        else {
          throw new JexlException(identifier, format(BAD_IDENTIFIER_MESSAGE, id));
        }
      }
      
      // Any other node type is not supported
      default -> throw new JexlException(node, "Expression not supported in CSEL selector");
    };
  }
}
