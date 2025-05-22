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

import org.apache.commons.jexl3.parser.ASTAddNode;
import org.apache.commons.jexl3.parser.ASTAndNode;
import org.apache.commons.jexl3.parser.ASTAnnotatedStatement;
import org.apache.commons.jexl3.parser.ASTAnnotation;
import org.apache.commons.jexl3.parser.ASTArguments;
import org.apache.commons.jexl3.parser.ASTArrayAccess;
import org.apache.commons.jexl3.parser.ASTArrayLiteral;
import org.apache.commons.jexl3.parser.ASTAssignment;
import org.apache.commons.jexl3.parser.ASTBitwiseAndNode;
import org.apache.commons.jexl3.parser.ASTBitwiseComplNode;
import org.apache.commons.jexl3.parser.ASTBitwiseOrNode;
import org.apache.commons.jexl3.parser.ASTBitwiseXorNode;
import org.apache.commons.jexl3.parser.ASTBlock;
import org.apache.commons.jexl3.parser.ASTBreak;
import org.apache.commons.jexl3.parser.ASTConstructorNode;
import org.apache.commons.jexl3.parser.ASTContinue;
import org.apache.commons.jexl3.parser.ASTDivNode;
import org.apache.commons.jexl3.parser.ASTEQNode;
import org.apache.commons.jexl3.parser.ASTERNode;
import org.apache.commons.jexl3.parser.ASTEWNode;
import org.apache.commons.jexl3.parser.ASTEmptyFunction;
import org.apache.commons.jexl3.parser.ASTEmptyMethod;
import org.apache.commons.jexl3.parser.ASTExtendedLiteral;
import org.apache.commons.jexl3.parser.ASTFalseNode;
import org.apache.commons.jexl3.parser.ASTForeachStatement;
import org.apache.commons.jexl3.parser.ASTFunctionNode;
import org.apache.commons.jexl3.parser.ASTGENode;
import org.apache.commons.jexl3.parser.ASTGTNode;
import org.apache.commons.jexl3.parser.ASTIdentifier;
import org.apache.commons.jexl3.parser.ASTIdentifierAccess;
import org.apache.commons.jexl3.parser.ASTIfStatement;
import org.apache.commons.jexl3.parser.ASTJexlScript;
import org.apache.commons.jexl3.parser.ASTJxltLiteral;
import org.apache.commons.jexl3.parser.ASTLENode;
import org.apache.commons.jexl3.parser.ASTLTNode;
import org.apache.commons.jexl3.parser.ASTMapEntry;
import org.apache.commons.jexl3.parser.ASTMapLiteral;
import org.apache.commons.jexl3.parser.ASTMethodNode;
import org.apache.commons.jexl3.parser.ASTModNode;
import org.apache.commons.jexl3.parser.ASTMulNode;
import org.apache.commons.jexl3.parser.ASTNENode;
import org.apache.commons.jexl3.parser.ASTNEWNode;
import org.apache.commons.jexl3.parser.ASTNRNode;
import org.apache.commons.jexl3.parser.ASTNSWNode;
import org.apache.commons.jexl3.parser.ASTNotNode;
import org.apache.commons.jexl3.parser.ASTNullLiteral;
import org.apache.commons.jexl3.parser.ASTNumberLiteral;
import org.apache.commons.jexl3.parser.ASTOrNode;
import org.apache.commons.jexl3.parser.ASTRangeNode;
import org.apache.commons.jexl3.parser.ASTReference;
import org.apache.commons.jexl3.parser.ASTReferenceExpression;
import org.apache.commons.jexl3.parser.ASTReturnStatement;
import org.apache.commons.jexl3.parser.ASTSWNode;
import org.apache.commons.jexl3.parser.ASTSetAddNode;
import org.apache.commons.jexl3.parser.ASTSetAndNode;
import org.apache.commons.jexl3.parser.ASTSetDivNode;
import org.apache.commons.jexl3.parser.ASTSetLiteral;
import org.apache.commons.jexl3.parser.ASTSetModNode;
import org.apache.commons.jexl3.parser.ASTSetMultNode;
import org.apache.commons.jexl3.parser.ASTSetOrNode;
import org.apache.commons.jexl3.parser.ASTSetSubNode;
import org.apache.commons.jexl3.parser.ASTSetXorNode;
import org.apache.commons.jexl3.parser.ASTSizeFunction;
import org.apache.commons.jexl3.parser.ASTSizeMethod;
import org.apache.commons.jexl3.parser.ASTStringLiteral;
import org.apache.commons.jexl3.parser.ASTSubNode;
import org.apache.commons.jexl3.parser.ASTTernaryNode;
import org.apache.commons.jexl3.parser.ASTTrueNode;
import org.apache.commons.jexl3.parser.ASTUnaryMinusNode;
import org.apache.commons.jexl3.parser.ASTVar;
import org.apache.commons.jexl3.parser.ASTWhileStatement;
import org.apache.commons.jexl3.parser.JexlNode;
import org.apache.commons.jexl3.parser.ParserVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common scaffolding for partial {@link ParserVisitor} implementations.
 *
 * @since 3.16
 */
public abstract class ParserVisitorSupport
    extends ParserVisitor
{
  protected static final int LEFT = 0;

  protected static final int RIGHT = 1;

  protected final Logger log = LoggerFactory.getLogger(getClass());

  @Override
  protected Object visit(final ASTJexlScript node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBlock node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTIfStatement node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTWhileStatement node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTContinue node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBreak node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTForeachStatement node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTReturnStatement node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTAssignment node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTVar node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTReference node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTTernaryNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTOrNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTAndNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBitwiseOrNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBitwiseXorNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBitwiseAndNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTEQNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNENode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTLTNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTGTNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTLENode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTGENode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTERNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNRNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSWNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNSWNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTEWNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNEWNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTAddNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSubNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTMulNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTDivNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTModNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTUnaryMinusNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTBitwiseComplNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNotNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTIdentifier node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNullLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTTrueNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTFalseNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTNumberLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTStringLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTExtendedLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTArrayLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTRangeNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTMapLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTMapEntry node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTEmptyFunction node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTEmptyMethod node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSizeFunction node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTFunctionNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTMethodNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSizeMethod node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTConstructorNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTArrayAccess node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTIdentifierAccess node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTArguments node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTReferenceExpression node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetAddNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetSubNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetMultNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetDivNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetModNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetAndNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetOrNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTSetXorNode node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTJxltLiteral node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTAnnotation node, final Object data) {
    return doVisit(node, data);
  }

  @Override
  protected Object visit(final ASTAnnotatedStatement node, final Object data) {
    return doVisit(node, data);
  }

  /**
   * Abstract method to be implemented by subclasses to handle visiting a node.
   * 
   * @param node the node to visit
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   */
  protected abstract Object doVisit(final JexlNode node, final Object data);

  /**
   * Helper method that uses Java 21 pattern matching for instanceof to handle different node types.
   * Subclasses can override this method to provide custom handling for specific node types.
   * 
   * @param node the node to visit
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object doVisitWithPatternMatching(final JexlNode node, final Object data) {
    // Use pattern matching for instanceof to handle different node types
    if (node instanceof ASTJexlScript script) {
      return handleJexlScript(script, data);
    }
    else if (node instanceof ASTBlock block) {
      return handleBlock(block, data);
    }
    else if (node instanceof ASTIfStatement ifStatement) {
      return handleIfStatement(ifStatement, data);
    }
    else if (node instanceof ASTWhileStatement whileStatement) {
      return handleWhileStatement(whileStatement, data);
    }
    else if (node instanceof ASTForeachStatement foreachStatement) {
      return handleForeachStatement(foreachStatement, data);
    }
    else if (node instanceof ASTReturnStatement returnStatement) {
      return handleReturnStatement(returnStatement, data);
    }
    else if (node instanceof ASTAssignment assignment) {
      return handleAssignment(assignment, data);
    }
    else if (node instanceof ASTVar var) {
      return handleVar(var, data);
    }
    else if (node instanceof ASTReference reference) {
      return handleReference(reference, data);
    }
    else if (node instanceof ASTTernaryNode ternary) {
      return handleTernary(ternary, data);
    }
    else if (node instanceof ASTBinaryOperator binaryOp) {
      return handleBinaryOperator(binaryOp, data);
    }
    else if (node instanceof ASTUnaryOperator unaryOp) {
      return handleUnaryOperator(unaryOp, data);
    }
    else if (node instanceof ASTLiteral literal) {
      return handleLiteral(literal, data);
    }
    else if (node instanceof ASTIdentifier identifier) {
      return handleIdentifier(identifier, data);
    }
    else if (node instanceof ASTFunction function) {
      return handleFunction(function, data);
    }
    else if (node instanceof ASTMethod method) {
      return handleMethod(method, data);
    }
    else {
      // Default handling for other node types
      log.debug("Unhandled node type: {}", node.getClass().getSimpleName());
      return null;
    }
  }

  /**
   * Helper method that uses Java 21 switch pattern matching to handle different node types.
   * This provides a more concise and expressive way to handle different node types compared to
   * the traditional if-else approach.
   * 
   * @param node the node to visit
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object doVisitWithSwitchPatternMatching(final JexlNode node, final Object data) {
    return switch (node) {
      case ASTJexlScript script -> handleJexlScript(script, data);
      case ASTBlock block -> handleBlock(block, data);
      case ASTIfStatement ifStatement -> handleIfStatement(ifStatement, data);
      case ASTWhileStatement whileStatement -> handleWhileStatement(whileStatement, data);
      case ASTForeachStatement foreachStatement -> handleForeachStatement(foreachStatement, data);
      case ASTReturnStatement returnStatement -> handleReturnStatement(returnStatement, data);
      case ASTAssignment assignment -> handleAssignment(assignment, data);
      case ASTVar var -> handleVar(var, data);
      case ASTReference reference -> handleReference(reference, data);
      case ASTTernaryNode ternary -> handleTernary(ternary, data);
      case ASTBinaryOperator binaryOp -> handleBinaryOperator(binaryOp, data);
      case ASTUnaryOperator unaryOp -> handleUnaryOperator(unaryOp, data);
      case ASTLiteral literal -> handleLiteral(literal, data);
      case ASTIdentifier identifier -> handleIdentifier(identifier, data);
      case ASTFunction function -> handleFunction(function, data);
      case ASTMethod method -> handleMethod(method, data);
      case null -> {
        log.warn("Null node encountered");
        yield null;
      }
      default -> {
        log.debug("Unhandled node type: {}", node.getClass().getSimpleName());
        yield null;
      }
    };
  }

  /**
   * Interface for binary operators to simplify pattern matching.
   * @since 3.60
   */
  protected interface ASTBinaryOperator extends JexlNode {
  }

  /**
   * Interface for unary operators to simplify pattern matching.
   * @since 3.60
   */
  protected interface ASTUnaryOperator extends JexlNode {
  }

  /**
   * Interface for literals to simplify pattern matching.
   * @since 3.60
   */
  protected interface ASTLiteral extends JexlNode {
  }

  /**
   * Interface for functions to simplify pattern matching.
   * @since 3.60
   */
  protected interface ASTFunction extends JexlNode {
  }

  /**
   * Interface for methods to simplify pattern matching.
   * @since 3.60
   */
  protected interface ASTMethod extends JexlNode {
  }

  // Implement these interfaces for the relevant node types
  static {
    // Binary operators
    ASTOrNode.class.asSubclass(ASTBinaryOperator.class);
    ASTAndNode.class.asSubclass(ASTBinaryOperator.class);
    ASTBitwiseOrNode.class.asSubclass(ASTBinaryOperator.class);
    ASTBitwiseXorNode.class.asSubclass(ASTBinaryOperator.class);
    ASTBitwiseAndNode.class.asSubclass(ASTBinaryOperator.class);
    ASTEQNode.class.asSubclass(ASTBinaryOperator.class);
    ASTNENode.class.asSubclass(ASTBinaryOperator.class);
    ASTLTNode.class.asSubclass(ASTBinaryOperator.class);
    ASTGTNode.class.asSubclass(ASTBinaryOperator.class);
    ASTLENode.class.asSubclass(ASTBinaryOperator.class);
    ASTGENode.class.asSubclass(ASTBinaryOperator.class);
    ASTERNode.class.asSubclass(ASTBinaryOperator.class);
    ASTNRNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSWNode.class.asSubclass(ASTBinaryOperator.class);
    ASTNSWNode.class.asSubclass(ASTBinaryOperator.class);
    ASTEWNode.class.asSubclass(ASTBinaryOperator.class);
    ASTNEWNode.class.asSubclass(ASTBinaryOperator.class);
    ASTAddNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSubNode.class.asSubclass(ASTBinaryOperator.class);
    ASTMulNode.class.asSubclass(ASTBinaryOperator.class);
    ASTDivNode.class.asSubclass(ASTBinaryOperator.class);
    ASTModNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetAddNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetSubNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetMultNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetDivNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetModNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetAndNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetOrNode.class.asSubclass(ASTBinaryOperator.class);
    ASTSetXorNode.class.asSubclass(ASTBinaryOperator.class);

    // Unary operators
    ASTUnaryMinusNode.class.asSubclass(ASTUnaryOperator.class);
    ASTBitwiseComplNode.class.asSubclass(ASTUnaryOperator.class);
    ASTNotNode.class.asSubclass(ASTUnaryOperator.class);

    // Literals
    ASTNullLiteral.class.asSubclass(ASTLiteral.class);
    ASTTrueNode.class.asSubclass(ASTLiteral.class);
    ASTFalseNode.class.asSubclass(ASTLiteral.class);
    ASTNumberLiteral.class.asSubclass(ASTLiteral.class);
    ASTStringLiteral.class.asSubclass(ASTLiteral.class);
    ASTSetLiteral.class.asSubclass(ASTLiteral.class);
    ASTExtendedLiteral.class.asSubclass(ASTLiteral.class);
    ASTArrayLiteral.class.asSubclass(ASTLiteral.class);
    ASTRangeNode.class.asSubclass(ASTLiteral.class);
    ASTMapLiteral.class.asSubclass(ASTLiteral.class);
    ASTMapEntry.class.asSubclass(ASTLiteral.class);
    ASTJxltLiteral.class.asSubclass(ASTLiteral.class);

    // Functions
    ASTEmptyFunction.class.asSubclass(ASTFunction.class);
    ASTSizeFunction.class.asSubclass(ASTFunction.class);
    ASTFunctionNode.class.asSubclass(ASTFunction.class);

    // Methods
    ASTEmptyMethod.class.asSubclass(ASTMethod.class);
    ASTSizeMethod.class.asSubclass(ASTMethod.class);
    ASTMethodNode.class.asSubclass(ASTMethod.class);
  }

  // Handler methods for different node types

  /**
   * Handle a JexlScript node.
   * @param script the script node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleJexlScript(final ASTJexlScript script, final Object data) {
    log.debug("Handling JexlScript");
    return null;
  }

  /**
   * Handle a Block node.
   * @param block the block node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleBlock(final ASTBlock block, final Object data) {
    log.debug("Handling Block");
    return null;
  }

  /**
   * Handle an IfStatement node.
   * @param ifStatement the if statement node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleIfStatement(final ASTIfStatement ifStatement, final Object data) {
    log.debug("Handling IfStatement");
    return null;
  }

  /**
   * Handle a WhileStatement node.
   * @param whileStatement the while statement node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleWhileStatement(final ASTWhileStatement whileStatement, final Object data) {
    log.debug("Handling WhileStatement");
    return null;
  }

  /**
   * Handle a ForeachStatement node.
   * @param foreachStatement the foreach statement node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleForeachStatement(final ASTForeachStatement foreachStatement, final Object data) {
    log.debug("Handling ForeachStatement");
    return null;
  }

  /**
   * Handle a ReturnStatement node.
   * @param returnStatement the return statement node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleReturnStatement(final ASTReturnStatement returnStatement, final Object data) {
    log.debug("Handling ReturnStatement");
    return null;
  }

  /**
   * Handle an Assignment node.
   * @param assignment the assignment node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleAssignment(final ASTAssignment assignment, final Object data) {
    log.debug("Handling Assignment");
    return null;
  }

  /**
   * Handle a Var node.
   * @param var the var node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleVar(final ASTVar var, final Object data) {
    log.debug("Handling Var");
    return null;
  }

  /**
   * Handle a Reference node.
   * @param reference the reference node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleReference(final ASTReference reference, final Object data) {
    log.debug("Handling Reference");
    return null;
  }

  /**
   * Handle a Ternary node.
   * @param ternary the ternary node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleTernary(final ASTTernaryNode ternary, final Object data) {
    log.debug("Handling Ternary");
    return null;
  }

  /**
   * Handle a BinaryOperator node.
   * @param binaryOp the binary operator node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleBinaryOperator(final ASTBinaryOperator binaryOp, final Object data) {
    log.debug("Handling BinaryOperator: {}", binaryOp.getClass().getSimpleName());
    return null;
  }

  /**
   * Handle a UnaryOperator node.
   * @param unaryOp the unary operator node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleUnaryOperator(final ASTUnaryOperator unaryOp, final Object data) {
    log.debug("Handling UnaryOperator: {}", unaryOp.getClass().getSimpleName());
    return null;
  }

  /**
   * Handle a Literal node.
   * @param literal the literal node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleLiteral(final ASTLiteral literal, final Object data) {
    log.debug("Handling Literal: {}", literal.getClass().getSimpleName());
    return null;
  }

  /**
   * Handle an Identifier node.
   * @param identifier the identifier node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleIdentifier(final ASTIdentifier identifier, final Object data) {
    log.debug("Handling Identifier");
    return null;
  }

  /**
   * Handle a Function node.
   * @param function the function node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleFunction(final ASTFunction function, final Object data) {
    log.debug("Handling Function: {}", function.getClass().getSimpleName());
    return null;
  }

  /**
   * Handle a Method node.
   * @param method the method node
   * @param data the data to pass to the visitor
   * @return the result of visiting the node
   * @since 3.60
   */
  protected Object handleMethod(final ASTMethod method, final Object data) {
    log.debug("Handling Method: {}", method.getClass().getSimpleName());
    return null;
  }
}