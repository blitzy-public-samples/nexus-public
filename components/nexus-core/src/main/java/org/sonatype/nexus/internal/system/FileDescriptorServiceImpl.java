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
package org.sonatype.nexus.internal.system;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.system.FileDescriptorProvider;
import org.sonatype.nexus.common.system.FileDescriptorService;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.KERNEL;

/**
 * Implementation of {@link FileDescriptorService} that checks system file descriptor limits.
 * 
 * @since 3.5
 */
@Named
@ManagedLifecycle(phase = KERNEL)
public class FileDescriptorServiceImpl
    extends StateGuardLifecycleSupport
    implements FileDescriptorService
{
  static final long MINIMUM_FILE_DESCRIPTOR_COUNT = 65536;

  static final String WARNING_HEADER =
      "WARNING: ****************************************************************************";
      
  static final long NOT_SUPPORTED = -1; // e.g. Windows does not have the concept of file descriptors

  private final long fileDescriptorCount;

  @Inject
  public FileDescriptorServiceImpl(@Nullable final FileDescriptorProvider fileDescriptorProvider) {
    this.fileDescriptorCount = fileDescriptorProvider instanceof FileDescriptorProvider provider
        ? provider.getFileDescriptorCount()
        : new ProcessProbeFileDescriptorProvider().getFileDescriptorCount();
  }

  @Override
  public void doStart() {
    // Launch file descriptor check in a virtual thread to minimize startup impact
    Thread.ofVirtual().name("file-descriptor-check").start(() -> checkFileDescriptorLimit());
  }
  
  /**
   * Performs the actual file descriptor limit check in a separate thread
   */
  private void checkFileDescriptorLimit() {
    if (!isFileDescriptorLimitOk()) {
      log.warn(WARNING_HEADER);
      log.warn(STR."WARNING: The open file descriptor limit is \{fileDescriptorCount} which is below the minimum recommended value of \{MINIMUM_FILE_DESCRIPTOR_COUNT}.");
      log.warn(STR."WARNING: System may experience issues with high load. Please see: http://links.sonatype.com/products/nexus/system-reqs#filehandles");
      log.warn(WARNING_HEADER);
    }
  }

  @Override
  public boolean isFileDescriptorLimitOk() {
    return fileDescriptorCount >= MINIMUM_FILE_DESCRIPTOR_COUNT || fileDescriptorCount == NOT_SUPPORTED;
  }

  @Override
  public long getFileDescriptorCount() {
    return fileDescriptorCount;
  }

  @Override
  public long getFileDescriptorRecommended() {
    return MINIMUM_FILE_DESCRIPTOR_COUNT;
  }
}