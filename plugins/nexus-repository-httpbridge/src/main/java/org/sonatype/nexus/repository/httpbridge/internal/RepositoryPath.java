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
package org.sonatype.nexus.repository.httpbridge.internal;

import org.sonatype.nexus.repository.BadRequestException;

import org.apache.commons.io.FilenameUtils;

/**
 * A utility class for parsing the repository name and remaining path out of a request URI.
 *
 * @since 3.0
 */
record RepositoryPath(String repositoryName, String remainingPath) {

  /**
   * Validate and parse the path.
   *
   * @throws BadRequestException if validation fails
   *
   * @return The parsed path
   */
  public static RepositoryPath parse(final String input) {
    return switch (input) {
      case null, "" -> throw new BadRequestException("Repository path must not be null or empty");
      case String s when !s.startsWith("/") -> 
          throw new BadRequestException("Repository path must start with '/'");
      case String s -> {
        int secondSlashIndex = s.indexOf('/', 1);
        if (secondSlashIndex == -1) {
          throw new BadRequestException("Repository path must have another '/' after initial '/'");
        }
        
        String repo = s.substring(1, secondSlashIndex);
        if (".".equals(repo) || "..".equals(repo)) {
          throw new BadRequestException("Repository path must not contain a relative token");
        }
        
        String path = s.substring(secondSlashIndex);
        String normalizedPath = FilenameUtils.normalize(path, true); // unixSeparator:true is necessary to make this work on Windows
        if (normalizedPath == null) {
          throw new BadRequestException("Repository path contains invalid relative tokens");
        }
        
        yield new RepositoryPath(repo, normalizedPath);
      }
    };
  }
  
  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "repositoryName='" + repositoryName + '\'' +
        ", remainingPath='" + remainingPath + '\'' +
        '}';
  }
}