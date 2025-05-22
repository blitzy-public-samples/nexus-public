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
package org.sonatype.nexus.siesta;

/**
 * Configuration for Virtual Thread context propagation.
 * 
 * This class provides configuration options for how context is propagated
 * across Virtual Thread boundaries, ensuring that security context, MDC logging
 * context, and other thread-local variables are properly maintained.
 *
 * @since 3.60
 */
public class VirtualThreadContextConfig
{
  private final boolean enabled;
  
  /**
   * Creates a new configuration with the specified enabled state.
   *
   * @param enabled whether Virtual Thread context propagation is enabled
   */
  public VirtualThreadContextConfig(final boolean enabled) {
    this.enabled = enabled;
  }
  
  /**
   * Returns whether Virtual Thread context propagation is enabled.
   *
   * @return true if enabled, false otherwise
   */
  public boolean isEnabled() {
    return enabled;
  }
}