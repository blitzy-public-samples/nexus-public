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
package com.sonatype.nexus.ssl.plugin.spi;

import org.sonatype.nexus.capability.CapabilityReference;

/**
 * Manages retrieve / update of TrustStore capabilities / type.
 * <p>
 * This interface is compatible with Java 21's enhanced security model and default strong encapsulation.
 * Implementations should ensure proper handling of security-sensitive operations when accessing
 * TrustStore capabilities.
 * <p>
 * When used with Apache Shiro 2.0.0, implementations must respect updated security constraints
 * and permission checks for TrustStore operations.
 * <p>
 * This interface works with updated cryptography providers in Java 21 for TrustStore operations.
 * Implementations should use modern cryptographic algorithms and practices.
 *
 * @since ssl 1.0
 */
public interface CapabilityManager
{

  /**
   * Retrieves a capability reference by its identifier.
   * <p>
   * Implementations should ensure proper access control checks are performed
   * in accordance with Apache Shiro 2.0.0 security model.
   *
   * @param id The capability identifier
   * @return The capability reference, or null if not found
   */
  CapabilityReference get(String id);

  /**
   * Enables or disables a capability by its identifier.
   * <p>
   * Implementations should ensure proper access control checks are performed
   * in accordance with Apache Shiro 2.0.0 security model.
   * <p>
   * When working with TrustStore capabilities, implementations must use cryptography
   * providers compatible with Java 21's security model.
   *
   * @param id The capability identifier
   * @param enabled True to enable the capability, false to disable it
   * @return The updated capability reference
   * @throws Exception If an error occurs during the operation
   */
  CapabilityReference enable(String id, boolean enabled) throws Exception;

}