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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of {@link AdminPasswordFileManager} that uses Java 21 features for improved performance.
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
  private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS = 
      PosixFilePermissions.fromString("rw-------");

  private final ApplicationDirectories applicationDirectories;
  private final Path adminPasswordFilePath;

  @Inject
  public AdminPasswordFileManagerImpl(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
    this.adminPasswordFilePath = Path.of(applicationDirectories.getWorkDirectory().getPath(), FILENAME);
  }

  @Override
  public boolean writeFile(String password) throws IOException {
    // Create a virtual thread to handle the file I/O operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> result = executor.submit(() -> {
        try {
          Path workDirPath = applicationDirectories.getWorkDirectory().toPath();
          
          // Create work directory if it doesn't exist
          if (!Files.exists(workDirPath)) {
            try {
              Files.createDirectories(workDirPath);
            } catch (IOException e) {
              log.error(STR."Failed to create work directory \{workDirPath}", e);
              return false;
            }
          }
          
          // Create the file if it doesn't exist and set permissions
          if (!Files.exists(adminPasswordFilePath)) {
            try {
              Files.createFile(adminPasswordFilePath);
              try {
                Files.setPosixFilePermissions(adminPasswordFilePath, OWNER_ONLY_PERMISSIONS);
              } catch (UnsupportedOperationException e) {
                // Not on a POSIX filesystem, fall back to standard file permissions
                adminPasswordFilePath.toFile().setReadable(true, true);
                adminPasswordFilePath.toFile().setWritable(true, true);
              }
            } catch (IOException e) {
              log.error(STR."Failed to create admin password file \{adminPasswordFilePath}", e);
              return false;
            }
          }
          
          // Write the password to the file
          try {
            log.info(STR."Writing admin user temporary password to \{adminPasswordFilePath}");
            Files.writeString(adminPasswordFilePath, password, StandardCharsets.UTF_8, 
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
          } catch (IOException e) {
            log.error("Failed to write temporary password to disk", e);
            return false;
          }
        } catch (Exception e) {
          log.error("Unexpected error writing admin password file", e);
          return false;
        }
      });
      
      return result.get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      // Use pattern matching for exceptions to handle different error types
      if (e instanceof IOException ioe) {
        throw ioe; // Rethrow IOException as specified in the interface
      } else if (e instanceof InterruptedException ie) {
        Thread.currentThread().interrupt(); // Restore interrupted status
        throw new IOException("Interrupted while writing admin password file", ie);
      } else {
        throw new IOException("Failed to write admin password file", e);
      }
    }
  }

  @Override
  public boolean exists() {
    return Files.exists(adminPasswordFilePath);
  }

  @Override
  public String getPath() {
    return adminPasswordFilePath.toAbsolutePath().toString();
  }

  @Override
  public String readFile() throws IOException {
    // Use a virtual thread to handle the file read operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> result = executor.submit(() -> {
        if (Files.exists(adminPasswordFilePath)) {
          try {
            return Files.readString(adminPasswordFilePath, StandardCharsets.UTF_8);
          } catch (IOException e) {
            log.error(STR."Failed to read admin password file \{adminPasswordFilePath}", e);
            throw e;
          }
        }
        return null;
      });
      
      return result.get(); // Wait for the virtual thread to complete
    } catch (Exception e) {
      // Use pattern matching for exceptions
      if (e instanceof IOException ioe) {
        throw ioe;
      } else if (e instanceof InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while reading admin password file", ie);
      } else {
        throw new IOException("Failed to read admin password file", e);
      }
    }
  }

  @Override
  public void removeFile() {
    // Use a virtual thread to handle the file deletion operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          if (Files.exists(adminPasswordFilePath)) {
            Files.delete(adminPasswordFilePath);
            log.debug(STR."Successfully deleted admin password file \{adminPasswordFilePath}");
          }
        } catch (IOException e) {
          log.error(STR."Failed to delete admin password file \{adminPasswordFilePath}", e);
        }
        return null;
      }).get(); // Wait for completion
    } catch (Exception e) {
      // Just log the error since the interface doesn't throw exceptions
      log.error(STR."Error during admin password file removal: \{e.getMessage()}", e);
    }
  }
}