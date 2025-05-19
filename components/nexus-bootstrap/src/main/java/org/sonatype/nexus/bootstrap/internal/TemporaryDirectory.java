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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Helper to ensure temporary directory is sane.
 *
 * @since 2.8
 */
public class TemporaryDirectory
{
  private TemporaryDirectory() {
    // empty
  }

  // NOTE: Do not reset the system property, we can not ensure this value will be used

  /**
   * Gets the system temporary directory and verifies it is usable.
   * 
   * @return The canonical temporary directory file
   * @throws IOException if the temporary directory cannot be accessed or used
   */
  public static File get() throws IOException {
    String location = System.getProperty("java.io.tmpdir", "tmp");
    File dir = new File(location).getCanonicalFile();
    DirectoryHelper.mkdir(dir.toPath());

    // ensure we can create temporary files in this directory
    try {
      // Use Java 21's enhanced file system APIs for temporary file creation
      // This approach handles file permissions more securely
      Path file;
      try {
        // Try to use POSIX permissions if supported by the file system
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
        FileAttribute<Set<PosixFilePermission>> fileAttributes = PosixFilePermissions.asFileAttribute(permissions);
        file = Files.createTempFile(dir.toPath(), "nexus-tmpcheck", ".tmp", fileAttributes);
      } catch (UnsupportedOperationException e) {
        // Fall back to standard method if POSIX permissions aren't supported
        file = Files.createTempFile(dir.toPath(), "nexus-tmpcheck", ".tmp");
      }
      
      // Verify the file exists and is writable
      if (!Files.isWritable(file)) {
        throw new IOException("Temporary directory is not writable: " + dir);
      }
      
      // Clean up the temporary file
      try {
        Files.delete(file);
      } catch (IOException e) {
        // Log but continue if we can't delete the temp file
        System.err.println("Warning: Could not delete temporary test file: " + file);
      }
    } catch (IOException e) {
      throw new IOException("Failed to create test file in temporary directory: " + dir, e);
    }
    
    return dir;
  }
}