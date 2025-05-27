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
package org.sonatype.nexus.repository.rest.internal.api;

/**
 * Interface that defines the methods from RepositoryDetailXO that need to be implemented
 * by classes that want to provide the same functionality but cannot extend RepositoryDetailXO directly
 * (such as Records).
 *
 * @since 3.30
 */
public interface RepositoryDetailXOExtension
{
  /**
   * Gets the repository name.
   *
   * @return the repository name
   */
  String getName();

  /**
   * Gets the repository type.
   *
   * @return the repository type
   */
  String getType();

  /**
   * Gets the repository format.
   *
   * @return the repository format
   */
  String getFormat();

  /**
   * Gets the repository URL.
   *
   * @return the repository URL
   */
  String getUrl();

  /**
   * Gets the repository status.
   *
   * @return the repository status
   */
  RepositoryStatusXO getStatus();
}