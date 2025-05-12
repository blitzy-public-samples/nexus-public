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
 * R format group repository configuration.
 * <p>
 * This class is compatible with Java 21 and works with the virtual thread implementation
 * in {@link org.sonatype.nexus.testsuite.testsupport.system.repository.RFormatRepositoryTestSystem}.
 * <p>
 * Group repositories in the R format allow aggregating multiple repositories into a single endpoint,
 * providing a unified view of R packages from multiple sources.
 *
 * @since 3.60
 */
public class RGroupRepositoryConfig
    extends GroupRepositoryConfigSupport<RGroupRepositoryConfig>
{
  /**
   * Creates a new R format group repository configuration.
   *
   * @param factory the factory function used to create the repository
   */
  public RGroupRepositoryConfig(final Function<RGroupRepositoryConfig, Repository> factory) {
    super(factory);
  }

  @Override
  public String getFormat() {
    return FORMAT_R;
  }
}