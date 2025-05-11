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
package org.sonatype.nexus.repository.maven.internal.utils;

import java.util.HashMap;
import java.util.Map;

import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;

import static org.apache.commons.lang3.StringUtils.EMPTY;

/**
 * Contains utilities shared by both the
 * {@link org.sonatype.nexus.repository.maven.internal.orient.OrientMavenVariableResolverAdapter}
 * and {@link org.sonatype.nexus.content.maven.internal.MavenVariableResolverAdapter}
 *
 * @since 3.25
 */
public class MavenVariableResolverAdapterUtil
{
  private static final String GROUP_ID = "groupId";

  private static final String ARTIFACT_ID = "artifactId";

  private static final String VERSION = "version";

  private static final String EXTENSION = "extension";

  private static final String CLASSIFIER = "classifier";

  private MavenVariableResolverAdapterUtil() {
    // Utility class, no instances
  }

  /**
   * Creates a map of Maven coordinate values from a {@link Coordinates} object.
   * Uses Java 21 pattern matching for null handling of classifier.
   *
   * @param coordinates the Maven coordinates to convert to a map
   * @return a map containing the coordinate values with standard keys
   */
  public static Map<String, String> createCoordinateMap(Coordinates coordinates) {
    // Using HashMap for mutable map creation as we need to add multiple entries
    // Map.of() would be limited to 10 entries and is immutable
    Map<String, String> coordMap = new HashMap<>();
    coordMap.put(GROUP_ID, coordinates.getGroupId());
    coordMap.put(ARTIFACT_ID, coordinates.getArtifactId());
    coordMap.put(VERSION, coordinates.getBaseVersion());
    coordMap.put(EXTENSION, coordinates.getExtension());
    
    // Using Java 21 pattern matching for instanceof with type patterns
    String classifier = switch (coordinates.getClassifier()) {
      case String s -> s;
      case null -> EMPTY;
    };
    coordMap.put(CLASSIFIER, classifier);
    
    return coordMap;
  }
}