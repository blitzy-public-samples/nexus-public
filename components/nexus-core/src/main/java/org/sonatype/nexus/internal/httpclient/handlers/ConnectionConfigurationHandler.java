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
package org.sonatype.nexus.internal.httpclient.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.crypto.secrets.SecretsFactory;
import org.sonatype.nexus.httpclient.config.ConnectionConfiguration;

import org.apache.ibatis.type.TypeHandler;

/**
 * MyBatis {@link TypeHandler} that maps a {@link ConnectionConfiguration} to/from JSON.
 * 
 * Updated for Java 21 compatibility with jakarta.inject annotations.
 *
 * @since 3.21
 */
@Named
@Singleton
public class ConnectionConfigurationHandler
    extends HttpClientConfigurationHandler<ConnectionConfiguration>
{
  /**
   * Creates a new ConnectionConfigurationHandler with the provided SecretsFactory.
   * 
   * @param secretsFactory factory for creating and managing secrets
   */
  @Inject
  public ConnectionConfigurationHandler(final SecretsFactory secretsFactory) {
    super(secretsFactory);
  }
  
  /**
   * Cleanup method to ensure proper resource management with Virtual Threads.
   * Delegates to parent implementation for ThreadLocal cleanup.
   */
  @Override
  public void cleanup() {
    super.cleanup();
  }
}