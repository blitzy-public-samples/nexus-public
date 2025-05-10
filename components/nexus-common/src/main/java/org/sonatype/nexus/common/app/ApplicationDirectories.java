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
package org.sonatype.nexus.common.app;

import java.io.File;
import java.nio.file.Path;

/**
 * Provides access to application directories.
 *
 * @since 2.8
 */
public interface ApplicationDirectories
{
  /**
   * Installation directory.
   * 
   * @return The installation directory as a File
   */
  File getInstallDirectory();

  /**
   * Installation directory as a Path.
   * 
   * @return The installation directory as a Path
   * @since 3.60
   */
  default Path getInstallPath() {
    return getInstallDirectory().toPath();
  }

  /**
   * Configuration directory.
   *
   * @param subsystem Sub-system name
   * @return The configuration directory as a File
   */
  File getConfigDirectory(String subsystem);

  /**
   * Configuration directory as a Path.
   *
   * @param subsystem Sub-system name
   * @return The configuration directory as a Path
   * @since 3.60
   */
  default Path getConfigPath(String subsystem) {
    return getConfigDirectory(subsystem).toPath();
  }

  /**
   * Temporary directory.
   * 
   * @return The temporary directory as a File
   */
  File getTemporaryDirectory();

  /**
   * Temporary directory as a Path.
   * 
   * @return The temporary directory as a Path
   * @since 3.60
   */
  default Path getTemporaryPath() {
    return getTemporaryDirectory().toPath();
  }

  /**
   * Work directory.
   * 
   * @return The work directory as a File
   */
  File getWorkDirectory();

  /**
   * Work directory as a Path.
   * 
   * @return The work directory as a Path
   * @since 3.60
   */
  default Path getWorkPath() {
    return getWorkDirectory().toPath();
  }

  /**
   * Work sub-directory.
   *
   * @param path Sub-directory path.
   * @param create True to create the directory if it does not exist.
   * @return The work sub-directory as a File
   */
  File getWorkDirectory(String path, boolean create);

  /**
   * Work sub-directory as a Path.
   *
   * @param path Sub-directory path.
   * @param create True to create the directory if it does not exist.
   * @return The work sub-directory as a Path
   * @since 3.60
   */
  default Path getWorkPath(String path, boolean create) {
    return getWorkDirectory(path, create).toPath();
  }

  /**
   * Work sub-directory.
   * 
   * @param path Sub-directory path.
   * @return The work sub-directory as a File
   */
  File getWorkDirectory(String path);

  /**
   * Work sub-directory as a Path.
   * 
   * @param path Sub-directory path.
   * @return The work sub-directory as a Path
   * @since 3.60
   */
  default Path getWorkPath(String path) {
    return getWorkDirectory(path).toPath();
  }
}