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
package org.sonatype.nexus.ui;

import java.net.URL;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.sisu.space.ClassSpace;

/**
 * Utility class for UI-related operations.
 *
 * @since 3.20
 */
public class UiUtil
{
  private static final Logger log = Logger.getLogger(UiUtil.class.getName());

  /**
   * Gets the path for the specified file in the classpath.
   * Uses Virtual Threads for I/O-bound operations to improve performance.
   *
   * @param filename the name of the file to find
   * @param space the ClassSpace to search in
   * @return the path to the requested file, or null if not found
   */
  public static String getPathForFile(final String filename, final ClassSpace space) {
    log.log(Level.FINE, STR."Searching for file: \{filename} in classpath");
    
    // Use Virtual Threads for I/O-bound operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> pathFuture = executor.submit(() -> {
        try (var entries = space.findEntries("static", filename, true)) {
          if (entries.hasMoreElements()) {
            URL url = entries.nextElement();
            String path = url.getPath();
            log.log(Level.FINE, STR."Found file: \{filename} at path: \{path}");
            return path;
          }
          log.log(Level.FINE, STR."File not found: \{filename}");
          return null;
        }
      });
      
      return pathFuture.get();
    } catch (Exception e) {
      log.log(Level.WARNING, STR."Error searching for file: \{filename}", e);
      return null;
    }
  }
}