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
package org.sonatype.nexus.coreui.internal.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.sonatype.nexus.common.log.LogManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Log information object
 *
 * @since 3.39
 */
public record LogXO(String fileName, long size, long lastModified) {
  private static final Logger log = LoggerFactory.getLogger(LogXO.class);

  /**
   * Creates a LogXO from a file path.
   */
  public static LogXO fromPath(Path path) {
    checkNotNull(path);
    
    String fileName;
    long size = -1;
    long lastModified = -1;
    
    try {
      // Use pattern matching to determine the file name prefix
      if (path.getParent() instanceof Path parent && 
          parent.getFileName() instanceof Path parentName && 
          LogManager.TASKS_PREFIX.startsWith(parentName.toString())) {
        fileName = LogManager.TASKS_PREFIX + path.getFileName().toString();
      }
      else if (path.getParent() instanceof Path parent && 
               parent.getFileName() instanceof Path parentName && 
               LogManager.REPLICATION_PREFIX.startsWith(parentName.toString())) {
        fileName = LogManager.REPLICATION_PREFIX + path.getFileName().toString();
      }
      else {
        fileName = path.getFileName().toString();
      }
      
      size = Files.size(path);
      lastModified = Files.getLastModifiedTime(path).toMillis();
    }
    catch (IOException e) {
      // Use String template (STR) for more readable logging
      log.debug(STR."Unable to get information about log file at \{path}");
      fileName = path.getFileName().toString();
    }
    
    return new LogXO(fileName, size, lastModified);
  }
  
  /**
   * Constructor for backward compatibility with existing code.
   * @deprecated Use {@link #fromPath(Path)} instead.
   */
  @Deprecated
  public LogXO(Path path) {
    this(fromPath(path));
  }
  
  /**
   * Copy constructor.
   */
  private LogXO(LogXO other) {
    this(other.fileName, other.size, other.lastModified);
  }
}