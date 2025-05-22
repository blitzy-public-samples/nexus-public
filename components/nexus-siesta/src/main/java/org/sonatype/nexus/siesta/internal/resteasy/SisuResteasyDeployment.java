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
package org.sonatype.nexus.siesta.internal.resteasy;

import org.jboss.resteasy.spi.ResteasyDeployment;

/**
 * Sisu {@link ResteasyDeployment}.
 * Updated for compatibility with RESTEasy 6.2.7.Final deployment model.
 *
 * @since 3.0
 */
public class SisuResteasyDeployment
    extends ResteasyDeployment
{
  /**
   * Initialize the deployment with a SisuResteasyProviderFactory.
   * This constructor ensures compatibility with RESTEasy 6.2.7.Final deployment model.
   */
  public SisuResteasyDeployment() {
    // Set the provider factory to our custom implementation
    providerFactory = new SisuResteasyProviderFactory();
    
    // Initialize deployment with default settings for RESTEasy 6.2.7.Final
    // This ensures compatibility with the current deployment model
    setRegisterBuiltin(true);
    setAsyncJobServiceEnabled(false);
    setWiderRequestMatching(false);
  }
}