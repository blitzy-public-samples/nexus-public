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
package org.sonatype.nexus.repository.security.internal.secrets.migration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.security.UserIdHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.security.internal.secrets.migration.RepositoriesSecretsMigrator.AUTHENTICATION_KEY;
import static org.sonatype.nexus.repository.security.internal.secrets.migration.RepositoriesSecretsMigrator.HTTP_CLIENT_KEY;
import static org.sonatype.nexus.repository.security.internal.secrets.migration.RepositoriesSecretsMigrator.PASSWORD_KEY;

@ExtendWith(MockitoExtension.class)
public class RepositoriesSecretsMigratorTest
    extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;

  @Mock
  private SecretsService secretsService;

  private MockedStatic<UserIdHelper> userIdHelperMock;

  private RepositoriesSecretsMigrator underTest;

  @BeforeEach
  public void setUp() {
    underTest = new RepositoriesSecretsMigrator(secretsService, repositoryManager);

    userIdHelperMock = mockStatic(UserIdHelper.class);

    userIdHelperMock.when(UserIdHelper::get).thenReturn("system");

    mockSecretsServiceFrom();
  }

  @AfterEach
  public void teardown() {
    userIdHelperMock.close();
  }

  @Test
  public void migrateProxyUpdatesPassword() throws Exception {
    mockRepositoryManager(mockProxy(null), mockProxy("my-password"));

    underTest.migrate();

    verify(secretsService).from("my-password");
    verify(repositoryManager, times(1)).update(any(Configuration.class));
  }

  @Test
  public void migrateProxyNotRequired() throws Exception {
    mockRepositoryManager(mockProxy(null));

    underTest.migrate();

    verify(repositoryManager, never()).update(any());
  }

  @Test
  public void migrateProxyAlreadyMigrated() throws Exception {
    mockRepositoryManager(mockProxy(null), mockProxy("_2"));

    underTest.migrate();

    verify(secretsService, never()).encrypt(any(), any(), any());
    verify(repositoryManager, never()).update(any());
  }
  
  @Test
  public void concurrentMigrationWithVirtualThreads() throws Exception {
    // Create repositories with passwords that need migration
    Repository[] repositories = new Repository[10];
    for (int i = 0; i < repositories.length; i++) {
      repositories[i] = mockProxy("password-" + i);
    }
    mockRepositoryManager(repositories);
    
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Track successful migrations
    AtomicInteger migrationsCompleted = new AtomicInteger(0);
    List<Exception> exceptions = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(5);
    
    // Launch multiple virtual threads to perform migrations concurrently
    for (int i = 0; i < 5; i++) {
      virtualThreadFactory.newThread(() -> {
        try {
          underTest.migrate();
          migrationsCompleted.incrementAndGet();
        }
        catch (Exception e) {
          exceptions.add(e);
        }
        finally {
          latch.countDown();
        }
      }).start();
    }
    
    // Wait for all threads to complete
    latch.await();
    
    // Verify all migrations completed successfully
    assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent migration");
    assertEquals(5, migrationsCompleted.get(), "All migration operations should complete successfully");
    
    // Verify the repository manager was called to update repositories
    verify(repositoryManager, times(repositories.length)).update(any(Configuration.class));
    
    // Verify each password was processed
    for (int i = 0; i < repositories.length; i++) {
      verify(secretsService).from("password-" + i);
    }
  }

  private void mockRepositoryManager(final Repository... repositories) {
    when(repositoryManager.browse()).thenReturn(Arrays.asList(repositories));
  }

  private void mockSecretsServiceFrom() {
    when(secretsService.from(any())).then(i -> {
      Secret secret = mock(Secret.class);
      when(secret.decrypt()).thenReturn(i.getArgument(0, String.class).toCharArray());
      when(secret.getId()).thenReturn(i.getArgument(0, String.class));
      return secret;
    });
  }

  private static Repository mockProxy(final String passwordKey) {
    Configuration configuration = new ConfigurationData();
    Repository repository = mock(Repository.class);
    when(repository.getConfiguration()).thenReturn(configuration);
    when(repository.getType()).thenReturn(new ProxyType());

    configuration.setRepositoryName(passwordKey);

    configuration.setAttributes(new HashMap<>());

    // Using pattern matching with instanceof to simplify the logic
    if (passwordKey instanceof String password) {
      if (!password.isEmpty()) {
        configuration.attributes(HTTP_CLIENT_KEY)
            .child(AUTHENTICATION_KEY)
            .set(PASSWORD_KEY, password);
      }
    }

    return repository;
  }
}