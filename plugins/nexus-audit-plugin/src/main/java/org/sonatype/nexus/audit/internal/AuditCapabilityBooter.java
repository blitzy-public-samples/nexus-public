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
package org.sonatype.nexus.audit.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.capability.CapabilityBooterSupport;
import org.sonatype.nexus.capability.CapabilityRegistry;

/**
 * Audit {@link CapabilityBooterSupport}.
 *
 * <p>Automatically registers the Audit capability during system startup.</p>
 *
 * <p>Updated for Java 21 compatibility with improved exception handling and
 * OSGi/Karaf 4.3.9 integration.</p>
 *
 * @since 3.1
 */
@Named
@Singleton
public class AuditCapabilityBooter
    extends CapabilityBooterSupport
{
  /**
   * Bootstraps the Audit capability by adding it to the registry if it doesn't already exist.
   * 
   * <p>This method is called automatically when the CapabilityRegistry is ready.</p>
   *
   * @param registry The capability registry to register with
   * @throws Exception If an error occurs during capability registration
   */
  @Override
  protected void boot(final CapabilityRegistry registry) throws Exception {
    maybeAddCapability(registry, AuditCapability.TYPE, true, null, null);
  }
}