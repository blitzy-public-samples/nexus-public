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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonStreamContext;

import static com.fasterxml.jackson.core.JsonPointer.forPath;
import static java.lang.StringTemplate.STR;
import static org.apache.commons.lang3.ArrayUtils.EMPTY_STRING_ARRAY;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Decorator class of a {@link JsonParser} allowing for maintaining and tracking the current path.
 *
 * @since 3.16
 */
public class CurrentPathJsonParser
    extends JsonParserDecorator
{
  private static final String JSON_PATH_SEPARATOR = "/";

  public CurrentPathJsonParser(final JsonParser jsonParser) {
    super(jsonParser);
  }

  /**
   * Retrieve path of current spot of the {@link JsonParser}.
   *
   * @return String of current path
   */
  public String currentPath() {
    JsonPointer pointer = currentPointer();
    if (pointer instanceof JsonPointer jp) {
      // Use String Template for more readable path construction
      String pointerStr = STR."{jp}";
      return toValidPath(pointerStr);
    }
    return JSON_PATH_SEPARATOR;
  }

  /**
   * Retrieve path of current spot of the {@link JsonParser}, split by their path separator.
   *
   * @return String array of path parts
   */
  public String[] currentPathInParts() {
    String currentPath = currentPath();
    if (currentPath instanceof String path && path.length() > 1) {
      // Use String Template for more readable path construction when extracting path parts
      String pathWithoutLeadingSeparator = STR."{path.substring(1)}";
      return pathWithoutLeadingSeparator.split(JSON_PATH_SEPARATOR);
    }
    return EMPTY_STRING_ARRAY;
  }

  /**
   * Retrieve pointer of current spot of the {@link JsonParser}, for fast parsing prevent calling this often as this
   * can potentially do a scan through the complete json object. Depending what the current point is it will
   * go traverse back up the path to the right path. See also {@link JsonPointer#forPath(JsonStreamContext, boolean)}
   *
   * @see JsonPointer#forPath(JsonStreamContext, boolean)
   * @return JsonPointer
   */
  public JsonPointer currentPointer() {
    return forPath(this.getParsingContext(), false);
  }

  /**
   * Return the current path of a {@link JsonPointer} or if no
   * path (like root) return {@link #JSON_PATH_SEPARATOR}.
   *
   * @param pointer JsonPointer
   * @return String of current path
   */
  private String currentPath(final JsonPointer pointer) {
    if (pointer instanceof JsonPointer jp) {
      // Use String Template for more readable path construction
      String path = STR."{jp}";
      return toValidPath(path);
    }
    return JSON_PATH_SEPARATOR;
  }

  /**
   * Ensures a valid path is returned, using JSON_PATH_SEPARATOR as fallback for blank paths.
   *
   * @param path The path to validate
   * @return A valid path string
   */
  private String toValidPath(final String path) {
    if (path instanceof String s && !isBlank(s)) {
      // Use the provided path if it's not blank
      return s;
    }
    // Return the separator as fallback for blank or null paths
    return JSON_PATH_SEPARATOR;
  }
}