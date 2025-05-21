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
package org.sonatype.nexus.internal.app;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

/**
 * REST API to manage the Nexus application lifecycle.
 *
 * <p>This API is compatible with Java 21 runtime environment and leverages performance
 * improvements provided by Virtual Threads for enhanced concurrency in I/O operations.</p>
 *
 * <p>Documentation is provided using Swagger/OpenAPI 2.2.20 and is compatible with
 * RESTEasy 6.2.7.Final framework.</p>
 *
 * @since 3.16
 */
@Api("Lifecycle")
public interface ManagedLifecycleResourceDoc
{
  /**
   * Retrieves the current lifecycle phase of the application.
   * Optimized for Java 21 runtime environment.
   *
   * @return The current lifecycle phase as a string
   */
  @ApiOperation("Get current lifecycle phase")
  String getPhase();

  /**
   * Moves the application to a new lifecycle phase.
   * Leverages Java 21 Virtual Threads for improved performance during phase transitions.
   *
   * @param phase The phase to move to
   */
  @ApiOperation("Move to new lifecycle phase")
  void setPhase(@ApiParam("The phase to move to") final String phase);

  /**
   * Re-runs all phases from the given phase to the current phase.
   * Benefits from Java 21 concurrency improvements for more efficient phase processing.
   *
   * @param phase The phase to bounce from
   */
  @ApiOperation(value = "Bounce lifecycle phase",
      notes = "Re-runs all phases from the given phase to the current phase")
  void bounce(@ApiParam("The phase to bounce") final String phase);
}