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

import org.sonatype.nexus.repository.Repository;

/**
 * Repository configuration interface for test support.
 * <p>
 * This interface provides methods to configure repository properties for testing purposes.
 * Implementations should be compatible with Java 21 runtime environment.
 *
 * @param <THIS> self-referential type parameter for fluent method chaining
 * @since 3.60.0
 */
public interface RepositoryConfig<THIS>
{
  /**
   * Sets the repository name.
   *
   * @param name the repository name
   * @return this instance for method chaining
   */
  THIS withName(final String name);

  /**
   * Gets the repository name.
   *
   * @return the repository name
   */
  String getName();

  /**
   * Gets the repository recipe.
   *
   * @return the repository recipe
   */
  String getRecipe();

  /**
   * Gets the repository format.
   *
   * @return the repository format
   */
  String getFormat();

  /**
   * Sets the blobstore name.
   *
   * @param blobstore the blobstore name
   * @return this instance for method chaining
   */
  THIS withBlobstore(final String blobstore);

  /**
   * Gets the blobstore name.
   *
   * @return the blobstore name
   */
  String getBlobstore();

  /**
   * Sets the datastore name.
   *
   * @param datastoreName the datastore name
   * @return this instance for method chaining
   */
  THIS withDatastoreName(final String datastoreName);

  /**
   * Gets the datastore name.
   *
   * @return the datastore name
   */
  String getDatastoreName();

  /**
   * Sets the online status.
   *
   * @param online the online status
   * @return this instance for method chaining
   */
  THIS withOnline(final Boolean online);

  /**
   * Gets the online status.
   *
   * @return the online status
   */
  Boolean isOnline();

  /**
   * Sets the strict content type validation flag.
   *
   * @param strictContentTypeValidation the strict content type validation flag
   * @return this instance for method chaining
   */
  THIS withStrictContentTypeValidation(final Boolean strictContentTypeValidation);

  /**
   * Gets the strict content type validation flag.
   *
   * @return the strict content type validation flag
   */
  Boolean isStrictContentTypeValidation();

  /**
   * Creates a repository with the configured properties.
   * <p>
   * When running with Java 21, implementations may leverage virtual threads for I/O operations.
   *
   * @return the created repository
   */
  Repository create();
}
