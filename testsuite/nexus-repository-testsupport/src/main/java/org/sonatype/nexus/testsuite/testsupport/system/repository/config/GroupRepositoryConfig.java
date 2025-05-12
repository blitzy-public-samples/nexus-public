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

/**
 * Configuration interface for group repositories in test support.
 * <p>
 * This interface provides methods to configure group repository members and write capabilities.
 * Compatible with Java 21 runtime environment.
 *
 * @param <THIS> self-referential type for fluent API pattern
 * @since 3.0
 */
public interface GroupRepositoryConfig<THIS>
    extends RepositoryConfig<THIS>
{
  /**
   * Sets the members of this group repository.
   *
   * @param members the repository names to include as members of this group
   * @return this instance for fluent method chaining
   */
  THIS withMembers(final String... members);

  /**
   * Gets the current members of this group repository.
   *
   * @return array of repository names that are members of this group
   */
  String[] getMembers();

  /**
   * Sets the group write member for this repository.
   * <p>
   * The group write member is the repository that will receive write operations
   * directed at this group repository.
   *
   * @param groupWriteMember the repository name to use as the write member
   * @return this instance for fluent method chaining
   */
  THIS withGroupWriteMember(final String groupWriteMember);

  /**
   * Gets the current group write member for this repository.
   *
   * @return the name of the repository that receives write operations
   */
  String getGroupWriteMember();
}
