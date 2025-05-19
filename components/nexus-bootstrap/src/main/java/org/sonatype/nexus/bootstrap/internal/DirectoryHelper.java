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
package org.sonatype.nexus.bootstrap.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper to create directories.
 *
 * @since 3.0
 */
public class DirectoryHelper
{
  private DirectoryHelper() {
    // empty
  }

  /**
   * Creates directories including any necessary but nonexistent parent directories.
   * 
   * Handles the case where the last element of the path exists but is a symbolic link to a directory.
   *
   * @param dir The directory path to create
   * @throws IOException If an I/O error occurs or if the path exists but is not a directory or a symbolic link to a directory
   */
  public static void mkdir(final Path dir) throws IOException {
    try {
      Files.createDirectories(dir);
    }
    catch (FileAlreadyExistsException e) {
      // This happens when the last element of path exists, but is a symlink.
      // Files.isDirectory() will follow symlinks by default and check if the target is a directory.
      if (!Files.isDirectory(dir)) {
        throw new IOException("Failed to create directory: " + dir + ". Path exists but is not a directory or a symbolic link to a directory", e);
      }
      // If we reach here, the path exists and is either a directory or a symlink to a directory, which is fine
    }
  }
}