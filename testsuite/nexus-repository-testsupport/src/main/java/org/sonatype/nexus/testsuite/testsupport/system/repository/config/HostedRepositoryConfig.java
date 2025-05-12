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

import org.sonatype.nexus.repository.config.WritePolicy;

/**
 * Interface for hosted repository configuration in test support.
 * 
 * @param <THIS> Self-referential type for fluent API pattern
 * 
 * @since 3.0
 * @java21.compatible This interface is compatible with Java 21 and supports testing with Virtual Threads
 */
public interface HostedRepositoryConfig<THIS>
    extends RepositoryConfig<THIS>
{
  /**
   * Sets the write policy for this repository.
   *
   * @param writePolicy the write policy to set
   * @return this instance for fluent method chaining
   */
  THIS withWritePolicy(final WritePolicy writePolicy);

  /**
   * Gets the current write policy for this repository.
   *
   * @return the current write policy
   */
  WritePolicy getWritePolicy();

  /**
   * Sets whether replication is enabled for this repository.
   *
   * @param replicationEnabled true to enable replication, false to disable
   * @return this instance for fluent method chaining
   */
  THIS withReplicationEnabled(final Boolean replicationEnabled);

  /**
   * Checks if replication is enabled for this repository.
   *
   * @return true if replication is enabled, false otherwise
   */
  Boolean isReplicationEnabled();
}