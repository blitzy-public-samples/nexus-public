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

import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.LogMarker;
import org.sonatype.nexus.rest.APIConstants;
import org.sonatype.nexus.rest.Resource;

import org.apache.shiro.authz.annotation.RequiresPermissions;

import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * Log REST resource.
 *
 * @since 2.7
 */
@Named
@Singleton
@Path(LogResource.RESOURCE_URI)
public class LogResource
    extends ComponentSupport
    implements Resource
{
  public static final String RESOURCE_URI = APIConstants.INTERNAL_API_PREFIX + "/logging/log";

  public static final String DEFAULT_MARK = "MARK";

  private final LogMarker logMarker;

  @Inject
  public LogResource(final LogMarker logMarker) {
    this.logMarker = Objects.requireNonNull(logMarker, "logMarker cannot be null");
  }

  /**
   * Marks the log with a message or default mark.
   */
  @POST
  @Path("/mark")
  @Consumes({TEXT_PLAIN})
  @RequiresPermissions("nexus:logging:create")
  public void mark(final String message) {
    // Using pattern matching with String type
    switch (message) {
      case null, "" -> logMarker.markLog(DEFAULT_MARK);
      case String msg -> logMarker.markLog(msg);
    }
    
    // Log the action using String Template
    log.debug(STR."Log marked with message: \{message == null || message.isEmpty() ? DEFAULT_MARK : message}");
  }
}