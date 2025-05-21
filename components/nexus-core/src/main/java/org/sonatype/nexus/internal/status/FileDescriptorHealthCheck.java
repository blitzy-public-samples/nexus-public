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
package org.sonatype.nexus.internal.status;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.system.FileDescriptorService;

import static java.lang.StringTemplate.STR;

/**
 * Health check that indicates if the file descriptor limit is below the recommended threshold
 *
 * @since 3.16
 */
@Named("File Descriptors")
@Singleton
public class FileDescriptorHealthCheck
    extends HealthCheckComponentSupport
{
  private final FileDescriptorService fileDescriptorService;

  @Inject
  public FileDescriptorHealthCheck(final FileDescriptorService fileDescriptorService) {
    this.fileDescriptorService = fileDescriptorService;
  }

  /**
   * Checks if the file descriptor limit is adequate.
   * 
   * Uses pattern matching to evaluate the current file descriptor count
   * against the recommended threshold.
   *
   * @return healthy result if count meets or exceeds the recommended limit, unhealthy otherwise
   */
  @Override
  protected Result check() {
    long recommended = fileDescriptorService.getFileDescriptorRecommended();
    long current = fileDescriptorService.getFileDescriptorCount();
    
    return switch (current) {
      case long count when count >= recommended -> Result.healthy();
      case long count when count < recommended -> {
        String message = STR."Recommended file descriptor limit is \{recommended} but count is \{count}";
        yield Result.unhealthy(message);
      }
    };
  }
}