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

import static org.sonatype.nexus.blobstore.api.BlobStoreManager.DEFAULT_BLOBSTORE_NAME;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Abstract support class for repository configuration in tests.
 * <p>
 * This class provides a fluent builder pattern for configuring repository instances
 * in test environments. It implements the RepositoryConfig interface and provides
 * default implementations for common configuration options.
 * <p>
 * Compatible with Java 21 and supports testing with JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 *
 * @param <THIS> The concrete implementation type for fluent method chaining
 */
public abstract class RepositoryConfigSupport<THIS>
    implements RepositoryConfig<THIS>
{
  private String name;

  private String blobstore = DEFAULT_BLOBSTORE_NAME;

  private String datastoreName = DEFAULT_DATASTORE_NAME;

  private Boolean online = true;

  private Boolean strictContentTypeValidation = true;

  private Function<THIS, Repository> factory;

  /**
   * Constructs a new RepositoryConfigSupport with the specified factory function.
   * 
   * @param factory The function that creates a Repository instance from this configuration
   */
  RepositoryConfigSupport(final Function<THIS, Repository> factory) {
    this.factory = factory;
  }

  /**
   * Sets the name of the repository.
   *
   * @param name The repository name
   * @return This instance for method chaining
   */
  @Override
  public THIS withName(final String name) {
    this.name = name;
    return toTHIS();
  }

  /**
   * Gets the name of the repository.
   *
   * @return The repository name
   */
  @Override
  public String getName() {
    return name;
  }

  /**
   * Sets the blobstore name for the repository.
   *
   * @param blobstore The blobstore name
   * @return This instance for method chaining
   */
  @Override
  public THIS withBlobstore(final String blobstore) {
    this.blobstore = blobstore;
    return toTHIS();
  }

  /**
   * Gets the blobstore name for the repository.
   *
   * @return The blobstore name
   */
  @Override
  public String getBlobstore() {
    return blobstore;
  }

  /**
   * Sets the datastore name for the repository.
   *
   * @param datastoreName The datastore name
   * @return This instance for method chaining
   */
  @Override
  public THIS withDatastoreName(final String datastoreName) {
    this.datastoreName = datastoreName;
    return toTHIS();
  }

  /**
   * Gets the datastore name for the repository.
   *
   * @return The datastore name
   */
  @Override
  public String getDatastoreName() {
    return datastoreName;
  }

  /**
   * Sets whether the repository is online.
   *
   * @param online True if the repository should be online, false otherwise
   * @return This instance for method chaining
   */
  @Override
  public THIS withOnline(final Boolean online) {
    this.online = online;
    return toTHIS();
  }

  /**
   * Gets whether the repository is online.
   *
   * @return True if the repository is online, false otherwise
   */
  @Override
  public Boolean isOnline() {
    return online;
  }

  /**
   * Sets whether strict content type validation is enabled for the repository.
   *
   * @param strictContentTypeValidation True if strict content type validation should be enabled, false otherwise
   * @return This instance for method chaining
   */
  @Override
  public THIS withStrictContentTypeValidation(final Boolean strictContentTypeValidation) {
    this.strictContentTypeValidation = strictContentTypeValidation;
    return toTHIS();
  }

  /**
   * Gets whether strict content type validation is enabled for the repository.
   *
   * @return True if strict content type validation is enabled, false otherwise
   */
  @Override
  public Boolean isStrictContentTypeValidation() {
    return strictContentTypeValidation;
  }

  /**
   * Creates a repository instance based on this configuration.
   * <p>
   * This method applies the factory function to create a repository instance with the configured properties.
   * When running on Java 21, repository operations may benefit from Virtual Threads for improved concurrency.
   *
   * @return The created repository instance
   */
  @Override
  public Repository create() {
    return factory.apply(toTHIS());
  }

  /**
   * Helper method to cast this instance to the parameterized type for fluent method chaining.
   *
   * @return This instance cast to the parameterized type
   */
  protected THIS toTHIS() {
    return (THIS) this;
  }
}
