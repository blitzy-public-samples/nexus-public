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
package org.sonatype.nexus.security.config;

import java.io.IOException;

/**
 * Manager for the admin password file.
 * 
 * @since 3.17
 */
public interface AdminPasswordFileManager
{
  /**
   * Check if the admin password file exists.
   *
   * @return true if the file exists, false otherwise
   */
  boolean exists();

  /**
   * Get the absolute path to the admin password file.
   *
   * @return the absolute path to the admin password file
   */
  String getPath();

  /**
   * Write the password to the admin password file.
   *
   * @param password the password to write
   * @return true if the write was successful, false otherwise
   * @throws IOException if an I/O error occurs
   */
  boolean writeFile(String password) throws IOException;

  /**
   * Read the password from the admin password file.
   *
   * @return the password from the file, or null if the file doesn't exist
   * @throws IOException if an I/O error occurs
   */
  String readFile() throws IOException;

  /**
   * Remove the admin password file if it exists.
   */
  void removeFile();
}