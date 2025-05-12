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
package org.sonatype.nexus.repository.raw;

/**
 * Helper methods for extracting component/asset coordinates for raw artifacts.
 *
 * @since 3.0
 */
public class RawCoordinatesHelper
{
  /**
   * Extracts the group part from a path.
   * <p>
   * The group is the path up to the last '/' character. If the path doesn't start with '/' or
   * if the only '/' is at the beginning, a leading '/' is added to the result.
   *
   * @param path the path to extract the group from
   * @return the group part of the path
   */
  public static String getGroup(String path) {
    int lastSlashIndex = path.lastIndexOf('/');
    
    // Handle special cases using pattern matching with switch
    return switch (path) {
      // When path doesn't have a slash or only has a slash at position 0
      case String s when lastSlashIndex == -1 -> "/";
      case String s when lastSlashIndex == 0 -> "/";
      
      // Normal case: extract the group part
      case String s when !s.startsWith("/") -> "/" + s.substring(0, lastSlashIndex);
      default -> path.substring(0, lastSlashIndex);
    };
  }

  private RawCoordinatesHelper() {
    // Don't instantiate
  }
}