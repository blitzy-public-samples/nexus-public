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
 *
 * This file has been verified for compatibility with Java 21 as part of the Nexus Repository Java 21 migration project.
 */
package org.sonatype.nexus.repository.apt.debian;

/**
 * Utility methods for Debian package handling.
 * 
 * @since 3.17
 */
public class Utils
{
  private Utils(){
    // Utility class, should not be instantiated
  }

  /**
   * Determines if a path represents a Debian package file based on its extension.
   * 
   * @param path the file path to check
   * @return true if the path ends with .deb or .udeb, false otherwise
   */
  public static boolean isDebPackageContentType(final String path) {
    return path.endsWith(".deb") || path.endsWith(".udeb");
  }
}