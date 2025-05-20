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
package org.sonatype.nexus.security.jwt;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Store for accessing the JWT secret.
 *
 * @since 3.38
 */
public interface SecretStore
{
  /**
   * Retrieve the JWT secret.
   *
   * @return the secret if it exists otherwise {@link Optional#empty}.
   * 
   * @apiNote This method may perform blocking I/O operations. When used in a Java 21 Virtual Thread context,
   *          the thread will automatically yield during I/O operations, allowing other Virtual Threads to execute.
   *          For non-blocking access, consider using {@link #getSecretAsync()} instead.
   */
  Optional<String> getSecret();

  /**
   * Asynchronously retrieve the JWT secret.
   *
   * @return a CompletableFuture that will complete with the secret if it exists, otherwise with {@link Optional#empty}.
   * 
   * @since 3.60
   * 
   * @apiNote This method is designed for non-blocking access to the JWT secret. It is particularly useful in
   *          high-throughput scenarios where blocking operations should be avoided. When running in a Java 21
   *          environment with Virtual Threads, this method enables efficient I/O operations without blocking
   *          carrier threads.
   */
  CompletableFuture<Optional<String>> getSecretAsync();

  /**
   * Set the JWT secret.
   * 
   * @param secret the secret to store
   * 
   * @apiNote This method may perform blocking I/O operations. When used in a Java 21 Virtual Thread context,
   *          the thread will automatically yield during I/O operations, allowing other Virtual Threads to execute.
   */
  void setSecret(final String secret);

  /**
   * Generate the new JWT secret by using the UUID.
   * 
   * @apiNote This method may perform blocking I/O operations. When used in a Java 21 Virtual Thread context,
   *          the thread will automatically yield during I/O operations, allowing other Virtual Threads to execute.
   */
  void generateNewSecret();
}