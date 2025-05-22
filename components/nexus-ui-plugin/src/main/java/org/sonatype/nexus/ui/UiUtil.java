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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
   * Gets the path for a file in the classpath using Virtual Threads for improved performance.
   *
   * @param filename the name of the file to find
   * @param space the ClassSpace to search in
   * @return the path to the requested file, or null if not found
   */
  public static String getPathForFile(final String filename, final ClassSpace space) {
    if (filename == null || space == null) {
      log.log(Level.WARNING, STR."Cannot search for file: filename=\{filename}, space=\{space}");
      return null;
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        try {
          Enumeration<URL> entries = space.findEntries("static", filename, true);
          if (entries != null && entries.hasMoreElements()) {
            URL url = entries.nextElement();
            String path = url.getPath();
            log.log(Level.FINE, STR."Found file \{filename} at path \{path}");
            return path;
          }
          log.log(Level.FINE, STR."File not found: \{filename}");
          return null;
        }
        catch (Exception e) {
          log.log(Level.WARNING, STR."Error searching for file \{filename}: \{e.getMessage()}", e);
          return null;
        }
      }, executor);

      return future.join();
    }
    catch (Exception e) {
      log.log(Level.SEVERE, STR."Failed to execute file search for \{filename}: \{e.getMessage()}", e);
      return null;
    }
  }
}