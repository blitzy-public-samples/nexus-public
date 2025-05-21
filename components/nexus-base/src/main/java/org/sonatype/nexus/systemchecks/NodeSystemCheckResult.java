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
package org.sonatype.nexus.systemchecks;

import java.util.Map;

import com.codahale.metrics.health.HealthCheck.Result;

/**
 * System check results.
 */
public record NodeSystemCheckResult(
    /**
     * The nodeId of the system which generated this result
     */
    String nodeId,
    
    /**
     * The hostname of the node which generated this result
     */
    String hostname,
    
    /**
     * The system check results for the node
     */
    Map<String, Result> systemChecks)
{
  /**
   * For backward compatibility - delegates to systemChecks()
   * @return the system check results for the node
   */
  public Map<String, Result> getResult() {
    return systemChecks();
  }
}
