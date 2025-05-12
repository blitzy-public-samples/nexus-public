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
package org.sonatype.nexus.testsuite.testsupport.system.repository.config;

import java.util.function.Function;

import org.sonatype.nexus.repository.Repository;

import static org.sonatype.nexus.testsuite.testsupport.system.RepositoryTestSystem.FORMAT_R;

/**
 * R format proxy repository configuration.
 * <p>
 * This class is compatible with Java 21 and can be used with virtual threads
 * for improved performance in I/O-bound operations. It provides configuration
 * for R format proxy repositories in test environments.
 * <p>
 * When used with {@link org.sonatype.nexus.testsuite.testsupport.system.repository.RFormatRepositoryTestSystem},
 * this configuration can leverage Java 21 virtual threads for concurrent repository operations.
 *
 * @since 3.60
 */
public class RProxyRepositoryConfig
    extends ProxyRepositoryConfigSupport<RProxyRepositoryConfig>
{
  /**
   * Creates a new R format proxy repository configuration.
   *
   * @param factory the factory function used to create the repository
   */
  public RProxyRepositoryConfig(final Function<RProxyRepositoryConfig, Repository> factory) {
    super(factory);
  }

  @Override
  public String getFormat() {
    return FORMAT_R;
  }
}