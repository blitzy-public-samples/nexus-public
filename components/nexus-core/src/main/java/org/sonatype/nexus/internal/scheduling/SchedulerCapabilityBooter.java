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
package org.sonatype.nexus.internal.scheduling;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.capability.CapabilityBooterSupport;
import org.sonatype.nexus.capability.CapabilityRegistry;

import org.eclipse.sisu.EagerSingleton;

/**
 * Creates {@link SchedulerCapability}.
 *
 * @since 3.0
 * @Java21Compatible This class has been verified for Java 21 compatibility
 */
@Named
@EagerSingleton
public class SchedulerCapabilityBooter
    extends CapabilityBooterSupport
{
  /**
   * Default constructor for dependency injection.
   * 
   * Explicitly defined to ensure compatibility with Java 21's class loading semantics
   * and updated Sisu/Guice dependency injection framework.
   */
  @Inject
  public SchedulerCapabilityBooter() {
    // Default constructor with explicit @Inject annotation for Java 21 compatibility
  }
  
  @Override
  protected void boot(final CapabilityRegistry registry) throws Exception {
    maybeAddCapability(registry, SchedulerCapabilityDescriptor.TYPE, true, null, null);
  }
}