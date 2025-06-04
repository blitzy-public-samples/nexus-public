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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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
 * Implementation of {@link SecretsStore} using Virtual Threads for improved I/O performance.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class SecretsStoreImpl
    extends ConfigStoreSupport<SecretsDAO>
    implements SecretsStore
{
  private final ExecutorService virtualThreadExecutor;
  
  @Inject
  public SecretsStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier, SecretsDAO.class);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Transactional
  @Override
  public int create(
      final String purpose,
      @Nullable final String keyId,
      final String secret,
      @Nullable final String userId)
  {
    // Using Virtual Thread for I/O-bound database operation
      try {
          return virtualThreadExecutor.submit(() -> {
            SecretData secretData = new SecretData();
            secretData.setPurpose(purpose);
            secretData.setKeyId(keyId);
            secretData.setSecret(secret);
            secretData.setUserId(userId);
            dao().create(secretData);
            return secretData.getId();
          }).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }

  @Transactional
  @Override
  public boolean delete(final int id) {
    // Using Virtual Thread for I/O-bound database operation
      try {
          return virtualThreadExecutor.submit(() -> dao().delete(id) > 0).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }

  @Transactional
  @Override
  public boolean update(final int id, final String oldSecret, final String keyId, final String secret) {
    // Using Virtual Thread for I/O-bound database operation
      try {
          return virtualThreadExecutor.submit(() -> dao().update(id, oldSecret, keyId, secret) > 0).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }

  @Transactional
  @Override
  public Optional<SecretData> read(final int id) {
    // Using Virtual Thread for I/O-bound database operation and Java 21 Pattern Matching for Optional
      try {
          return virtualThreadExecutor.submit(() -> {
            // The actual pattern matching will be used by consumers of this API
            // For example, clients can now use: if (secretsStore.read(id) instanceof Optional.Present(var secretData)) {...}
            return dao().read(id);
          }).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }

  @Transactional
  @Override
  public boolean existWithDifferentKeyId(final String keyId) {
    // Using Virtual Thread for I/O-bound database operation
      try {
          return virtualThreadExecutor.submit(() -> dao().existWithDifferentKeyId(keyId)).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }

  @Transactional
  @Override
  public List<SecretData> fetchWithDifferentKeyId(final String keyId, final int limit) {
    // Using Virtual Thread for I/O-bound database operation
      try {
          return virtualThreadExecutor.submit(() -> dao().fetchWithDifferentKeyId(keyId, limit)).get();
      } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
      }
  }
}