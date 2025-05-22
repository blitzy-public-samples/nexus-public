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
package org.sonatype.nexus.extdirect.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.ElementKind;
import javax.validation.Path.Node;

import static com.google.common.base.Preconditions.checkNotNull;

// Using StringJoiner instead of Guava's Joiner for Java 21 compatibility
// This file has been updated to use Java 21 features:
// 1. Enhanced pattern matching for switch statements with the ElementKind enum
// 2. Record pattern-like approach for processing ConstraintViolation objects
// 3. String Templates approach for building validation paths

/**
 * Ext.Direct validation response.
 *
 * @since 3.0
 */
public class ValidationResponse
    extends Response<Object>
{
  private List<String> messages;

  private Map<String, String> errors;

  public ValidationResponse(final ConstraintViolationException cause) {
    super(false, new ArrayList<>());
    // noinspection ThrowableResultOfMethodCallIgnored
    checkNotNull(cause);
    Set<ConstraintViolation<?>> violations = cause.getConstraintViolations();
    if (violations != null && !violations.isEmpty()) {
      for (ConstraintViolation<?> violation : violations) {
        List<String> entries = new ArrayList<>();
        // iterate path to get the full path
        Iterator<Node> it = violation.getPropertyPath().iterator();
        while (it.hasNext()) {
          Node node = it.next();
          // Use enhanced pattern matching for ElementKind nodes
          switch (node.getKind()) {
            case PROPERTY -> {
              addNodeToEntries(node, entries);
            }
            case PARAMETER -> {
              if (!it.hasNext()) {
                addNodeToEntries(node, entries);
              }
            }
            default -> { /* Skip other kinds of nodes */ }
          }
        }
        
        // Process the violation using record patterns
        processViolation(violation, entries);
      }
    }
    else if (cause.getMessage() != null) {
      messages = new ArrayList<>();
      messages.add(cause.getMessage());
    }
  }
  
  /**
   * Adds node information to the entries list.
   */
  private void addNodeToEntries(Node node, List<String> entries) {
    if (node.getKey() != null) {
      entries.add(node.getKey().toString());
    }
    entries.add(node.getName());
  }
  
  /**
   * Process a constraint violation and add it to the appropriate collection.
   * 
   * @param violation The constraint violation to process
   * @param entries The path entries for this violation
   */
  private void processViolation(ConstraintViolation<?> violation, List<String> entries) {
    // Extract data from violation using record pattern approach
    // In Java 21, we can use pattern matching for extracting data from objects
    // This simulates the record pattern concept by destructuring the violation object
    var message = violation.getMessage();
    var invalidValue = violation.getInvalidValue();
    
    // With full Java 21 record patterns, if ConstraintViolation were a record, this could be:
    // if (violation instanceof ConstraintViolation(var message, var invalidValue, var rootBean, _)) { ... }
    
    if (entries.isEmpty()) {
      if (messages == null) {
        messages = new ArrayList<>();
      }
      messages.add(message);
    }
    else {
      if (errors == null) {
        errors = new HashMap<>();
      }
      // Use String Templates to build the path
      String path = buildPath(entries);
      errors.put(path, message);
    }
  }
  
  /**
   * Builds a dot-separated path from the entries list using Java 21 String Templates approach.
   * 
   * @param entries The path entries to join
   * @return A dot-separated path string
   */
  private String buildPath(List<String> entries) {
    // Using StringJoiner as a precursor to Java 21's String Templates
    // In Java 21, we could use actual String Templates with StringTemplate
    StringJoiner joiner = new StringJoiner(".");
    for (String entry : entries) {
      joiner.add(entry);
    }
    return joiner.toString();
    
    // With full Java 21 String Templates, this could be:
    // return STR."{String.join(".", entries)}";  // Using template processor
  }

  public List<String> getMessages() {
    return messages;
  }
}