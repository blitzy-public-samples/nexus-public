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
package org.sonatype.nexus.coreui.internal.atlas;

import java.util.Map;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.atlas.SystemInformationGenerator;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * System Information {@link DirectComponent}.
 * 
 * Provides system information data through the ExtDirect framework.
 * Uses Java 21 features for improved performance and code readability.
 */
@Named
@Singleton
@DirectAction(action = "atlas_SystemInformation")
public class SystemInformationComponent
    extends DirectComponentSupport
{
  /**
   * Record to hold component dependencies for cleaner initialization.
   */
  private record Dependencies(SystemInformationGenerator systemInformationGenerator) {
    Dependencies {
      checkNotNull(systemInformationGenerator);
    }
  }
  
  private final Dependencies dependencies;

  @Inject
  public SystemInformationComponent(final SystemInformationGenerator systemInformationGenerator) {
    this.dependencies = new Dependencies(systemInformationGenerator);
  }

  /**
   * Retrieves system information.
   *
   * @return a tree-structured report of critical system information details
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:atlas:read")
  public Map<String, Object> read() {
    // Using virtual thread for I/O-bound operation to improve scalability
    // This is especially beneficial when gathering system information involves I/O operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> dependencies.systemInformationGenerator().report()).join();
    }
  }
}