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
package org.sonatype.nexus.repository.json;

import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.collect.NestedAttributesMap;

import com.fasterxml.jackson.core.JsonParser;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static java.util.Objects.isNull;

/**
 * {@link JsonParser} that holds state for communicating with a {@link NestedAttributesMap}. This mostly will be
 * valuable for parsing and merging multiple json objects into a give {@link NestedAttributesMap}
 *
 * @since 3.16
 */
public class NestedAttributesMapJsonParser
    extends CurrentPathJsonParser
{
  private final NestedAttributesMap root;

  private boolean mappingInsideArray;

  private boolean defaultMapping;

  /**
   * Record to represent the parser state for a path part and its corresponding object
   */
  private record PathPart(String part, Object value) {}

  public NestedAttributesMapJsonParser(final JsonParser jsonParser, final NestedAttributesMap root) {
    super(checkNotNull(jsonParser));
    this.root = checkNotNull(root);
  }

  /**
   * Attempt to retrieve the {@link NestedAttributesMap} that is associated with
   * the current path, if parts of the path are not existing they will
   * be manually created by the {@link NestedAttributesMap#child(String)} for that path part.
   *
   * @return NestedAttributesMap or null if we are mapping inside an array or no child was found.
   */
  @Nullable
  public NestedAttributesMap getChildFromRoot() {
    // if we are inside an array we don't have a map value to return
    if (isMappingInsideArray()) {
      return null;
    }

    // we start with the root as initial existing child to go down it's path
    NestedAttributesMap existingChild = root;

    // remove the first "/" and then split on any leftover
    for (String part : currentPathInParts()) {
      Object o = existingChild.get(part);
      
      // Create a PathPart record for pattern matching
      PathPart pathPart = new PathPart(part, o);
      
      // Using record pattern matching to check the value type
      switch (pathPart) {
        case PathPart(String p, null) -> existingChild = existingChild.child(p);
        case PathPart(String p, Map<?, ?> m) -> existingChild = existingChild.child(p);
        default -> { /* Skip this path part as it's not a Map or null */ }
      }
    }

    return existingChild.equals(root) ? null : existingChild;
  }

  /**
   * Inform parser that the current position is inside an array.
   */
  public void markMappingInsideArray() {
    mappingInsideArray = true;
  }

  /**
   * Inform parser that the current position has exited an array.
   */
  public void unMarkMappingInsideArray() {
    mappingInsideArray = false;
  }

  /**
   * Inform parser that no special mapping should occur but the default Jackson mapping should be followed.
   */
  public void enableDefaultMapping() {
    defaultMapping = true;
  }

  /**
   * Inform parser that special mapping should occur over using the default Jackson mapping.
   */
  public void disableDefaultMapping() {
    defaultMapping = false;
  }

  /**
   * Get the root NestedAttributesMap.
   * 
   * @return the root NestedAttributesMap
   */
  public NestedAttributesMap getRoot() {
    return root;
  }

  /**
   * Check if the parser is currently mapping inside an array.
   * 
   * @return true if mapping inside an array, false otherwise
   */
  public boolean isMappingInsideArray() {
    return mappingInsideArray;
  }

  /**
   * Check if default mapping is enabled.
   * 
   * @return true if default mapping is enabled, false otherwise
   */
  public boolean isDefaultMapping() {
    return defaultMapping;
  }

  /**
   * Get a formatted error message for JSON parsing issues.
   * 
   * @param message the base error message
   * @param path the current JSON path
   * @return formatted error message with path information
   */
  protected String formatErrorMessage(String message, String path) {
    return STR."\{message} at path: \{path}";
  }
}