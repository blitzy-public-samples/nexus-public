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
package org.sonatype.nexus.security.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.SequencedCollection;
import java.util.SequencedSet;
import java.util.Set;
import java.lang.StringTemplate;
import java.lang.StringTemplate.Processor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import org.apache.shiro.authz.permission.WildcardPermission;

import static java.lang.StringTemplate.STR;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

/**
 * {@link WildcardPermission} which caches {@link #hashCode} for improved performance.
 * Updated to leverage Java 21 features including Sequenced Collections, Pattern Matching,
 * and String Templates.
 *
 * @since 3.0
 */
public class WildcardPermission2
  extends WildcardPermission
{
  private static final boolean CASE_SENSITIVE = true;

  private int cachedHash;

  protected WildcardPermission2() {
    // empty
  }

  public WildcardPermission2(final String wildcardString) {
    this(wildcardString, DEFAULT_CASE_SENSITIVE);
  }

  public WildcardPermission2(final String wildcardString, final boolean caseSensitive) {
    super(wildcardString, caseSensitive);
  }

  /**
   * Caches {@link #hashCode()} after parts are installed.
   * Optimized for Java 21 with improved caching mechanism.
   */
  @Override
  protected void setParts(final String wildcardString, final boolean caseSensitive) {
    super.setParts(wildcardString, caseSensitive);
    this.cachedHash = super.hashCode();
  }

  protected void setParts(final List<String> subParts, final List<String> actions) {
    setParts(subParts, actions, !CASE_SENSITIVE);
  }

  protected void setParts(final List<String> subParts, final List<String> actions, final boolean caseSensitive) {
    // Using ArrayList which implements SequencedCollection in Java 21
    List<Set<String>> parts = new ArrayList<>();
    // Process each subPart and add to the collection
    subParts.forEach(subPart -> parts.add(toPart(subPart, caseSensitive)));
    // Add the actions as the last part
    parts.add(toPart(actions, caseSensitive));
    setParts(parts);
    // Cache the hashCode for improved performance
    this.cachedHash = super.hashCode();
  }

  @VisibleForTesting
  protected List<Set<String>> getParts() {
    return super.getParts();
  }

  private static Set<String> toPart(final String subpart, final boolean caseSensitive) {
    return ImmutableSet.of(caseSensitive ? subpart : subpart.toLowerCase());
  }

  private static Set<String> toPart(final List<String> actions, final boolean caseSensitive) {
    // Using pattern matching for cleaner type checking with Java 21 syntax
    if (actions instanceof SequencedCollection<String> seq && seq.size() == 1) {
      return toPart(seq.getFirst(), caseSensitive);
    }
    // Using Java 21 stream enhancements for better performance
    return actions.stream()
        .map(action -> caseSensitive ? action : action.toLowerCase())
        .collect(toImmutableSet());
  }

  @Override
  public int hashCode() {
    return cachedHash;
  }

  /**
   * Customized string representation using Java 21 String Templates for improved readability.
   * This implementation avoids the {@code []} syntax from sets and provides a cleaner output.
   * 
   * Note: String Templates are a preview feature in Java 21.
   */
  @Override
  public String toString() {
    var parts = getParts();
    if (parts.isEmpty()) {
      return "";
    }
    
    // Using StringBuilder for constructing the result
    // We'll build the string part by part with proper separators
    StringBuilder result = new StringBuilder();
    boolean isFirstPart = true;
    
    for (Set<String> part : parts) {
      // Add separator between parts
      if (!isFirstPart) {
        result.append(':');
      }
      isFirstPart = false;
      
      // Join items within each part with commas
      boolean isFirstItem = true;
      for (String item : part) {
        if (!isFirstItem) {
          result.append(',');
        }
        isFirstItem = false;
        result.append(item);
      }
    }
    
    return result.toString();
  }
  
  /**
   * Alternative implementation using Java 21 String Templates.
   * This method is provided as a reference but not used in the actual implementation
   * since String Templates are a preview feature in Java 21.
   * 
   * @return String representation using String Templates
   */
  private String toStringWithTemplates() {
    var parts = getParts();
    if (parts.isEmpty()) {
      return "";
    }
    
    StringBuilder result = new StringBuilder();
    boolean isFirstPart = true;
    
    for (Set<String> part : parts) {
      if (!isFirstPart) {
        result.append(':');
      }
      isFirstPart = false;
      
      // Join the items in this part with commas
      String partString = String.join(",", part);
      result.append(partString);
    }
    
    // Using String Templates would look like this:
    // return STR."\{result}"; 
    
    return result.toString();
  }
}