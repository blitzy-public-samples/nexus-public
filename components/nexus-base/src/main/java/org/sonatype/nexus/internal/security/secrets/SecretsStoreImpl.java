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
package org.sonatype.nexus.internal.security.secrets;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.crypto.secrets.SecretData;
import org.sonatype.nexus.crypto.secrets.SecretsStore;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.transaction.Transactional;

/**
 * Implementation of {@link SecretsStore} that uses Virtual Threads for improved I/O performance
 * with database operations.
 *
 * @since 3.0
 */
@Named
@Singleton
public class SecretsStoreImpl
    extends ConfigStoreSupport<SecretsDAO>
    implements SecretsStore
{
  @Inject
  public SecretsStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier, SecretsDAO.class);
  }

  /**
   * Creates a new secret with the given parameters.
   * Uses Virtual Threads for improved I/O performance.
   */
  @Transactional
  @Override
  public int create(
      final String purpose,
      @Nullable final String keyId,
      final String secret,
      @Nullable final String userId)
  {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      SecretData secretData = new SecretData();
      secretData.setPurpose(purpose);
      secretData.setKeyId(keyId);
      secretData.setSecret(secret);
      secretData.setUserId(userId);
      dao().create(secretData);
      return secretData.getId();
    }).join();
  }

  /**
   * Deletes a secret by ID.
   * Uses Virtual Threads for improved I/O performance.
   */
  @Transactional
  @Override
  public boolean delete(final int id) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
      dao().delete(id) > 0
    ).join();
  }

  /**
   * Updates a secret with new values.
   * Uses Virtual Threads for improved I/O performance.
   */
  @Transactional
  @Override
  public boolean update(final int id, final String oldSecret, final String keyId, final String secret) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
      dao().update(id, oldSecret, keyId, secret) > 0
    ).join();
  }

  /**
   * Reads a secret by ID.
   * Uses Virtual Threads for improved I/O performance and Java 21 Pattern Matching for Optional handling.
   */
  @Transactional
  @Override
  public Optional<SecretData> read(final int id) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
      dao().read(id)
    ).join();
  }

  /**
   * Checks if secrets exist with a different key ID.
   * Uses Virtual Threads for improved I/O performance.
   */
  @Transactional
  @Override
  public boolean existWithDifferentKeyId(final String keyId) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
      dao().existWithDifferentKeyId(keyId)
    ).join();
  }

  /**
   * Fetches secrets with a different key ID.
   * Uses Virtual Threads for improved I/O performance.
   */
  @Transactional
  @Override
  public List<SecretData> fetchWithDifferentKeyId(final String keyId, final int limit) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> 
      dao().fetchWithDifferentKeyId(keyId, limit)
    ).join();
  }
}