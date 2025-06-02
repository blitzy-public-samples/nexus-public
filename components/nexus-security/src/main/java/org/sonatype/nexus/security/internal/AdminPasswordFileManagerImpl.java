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
package org.sonatype.nexus.security.internal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

/**
 * Implementation of {@link AdminPasswordFileManager} that uses Java 21 Virtual Threads
 * for improved I/O performance.
 *
 * @since 3.17
 */
@Named
@Singleton
public class AdminPasswordFileManagerImpl
  extends ComponentSupport
  implements AdminPasswordFileManager
{
  private static final String FILENAME = "admin.password";

  public final ApplicationDirectories applicationDirectories;

  private final File adminPasswordFile;
  
  private final ExecutorService executor;

  @Inject
  public AdminPasswordFileManagerImpl(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
    adminPasswordFile = new File(applicationDirectories.getWorkDirectory(), FILENAME);
    // Create a virtual thread per task executor for file I/O operations
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public boolean writeFile(String password) throws IOException {
    File workdir = applicationDirectories.getWorkDirectory();
    if (!workdir.isDirectory() && !workdir.mkdirs()) {
      log.error("Failed to create work directory {}", workdir);
      return false;
    }

    try {
      // Use virtual thread for file I/O operation
      executor.submit(() -> {
        try {
          Path filePath = adminPasswordFile.toPath();
          
          // Create the file if it doesn't exist
          if (!Files.exists(filePath)) {
            Files.createFile(filePath);
            setFilePermissions(filePath);
          }
          
          log.info("Writing admin user temporary password to {}", adminPasswordFile.toString());
          Files.writeString(filePath, password, StandardCharsets.UTF_8);
          return true;
        }
        catch (Exception e) {
          log.error("Failed to write temporary password to disk", e);
          return false;
        }
      }).get(); // Wait for completion
      
      return true;
    }
    catch (Exception e) {
      log.error("Failed to write temporary password to disk", e);
      return false;
    }
  }

  /**
   * Sets appropriate file permissions based on the platform.
   * Uses POSIX permissions on compatible systems, falls back to Java's setReadable for others.
   */
  private void setFilePermissions(Path filePath) throws IOException {
    try {
      // Try to use POSIX permissions (Unix/Linux/macOS)
      Set<PosixFilePermission> permissions = Set.of(OWNER_READ, OWNER_WRITE);
      Files.setPosixFilePermissions(filePath, permissions);
    }
    catch (UnsupportedOperationException e) {
      // Fall back to basic Java file permissions for non-POSIX systems (Windows)
      File file = filePath.toFile();
      if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
        log.warn("Could not set proper permissions on {}", filePath);
      }
    }
  }

  @Override
  public boolean exists() {
    return adminPasswordFile.exists();
  }

  @Override
  public String getPath() {
    return adminPasswordFile.getAbsolutePath();
  }

  @Override
  public String readFile() throws IOException {
    Path filePath = adminPasswordFile.toPath();
    if (Files.exists(filePath)) {
      try {
        // Use virtual thread for file I/O operation
        return executor.submit(() -> 
          Files.readString(filePath, StandardCharsets.UTF_8)
        ).get(); // Wait for completion
      }
      catch (Exception e) {
        log.error("Failed to read admin password file", e);
        throw new IOException("Failed to read admin password file", e);
      }
    }

    return null;
  }

  @Override
  public void removeFile() {
    if (adminPasswordFile.exists()) {
      try {
        // Use virtual thread for file I/O operation
        executor.submit(() -> {
          try {
            Files.delete(adminPasswordFile.toPath());
            return true;
          }
          catch (IOException e) {
            log.error("Failed to delete admin.password file {}", adminPasswordFile, e);
            return false;
          }
        }).get(); // Wait for completion
      }
      catch (Exception e) {
        log.error("Failed to delete admin.password file {}", adminPasswordFile, e);
      }
    }
  }
}