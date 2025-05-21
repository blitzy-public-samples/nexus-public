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
package org.sonatype.nexus.internal.security.apikey;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.db.DatabaseCheck;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.internal.security.apikey.store.ApiKeyStore;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.KeyValueEvent;
import org.sonatype.nexus.security.UserPrincipalsExpired;
import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.authc.apikey.ApiKey;
import org.sonatype.nexus.security.authc.apikey.ApiKeyFactory;
import org.sonatype.nexus.security.user.UserNotFoundException;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;
import static org.sonatype.nexus.scheduling.CancelableHelper.checkCancellation;

@ManagedLifecycle(phase = TASKS)
@Named
@Singleton
public class ApiKeyServiceImpl
    extends StateGuardLifecycleSupport
    implements ApiKeyInternalService, EventAware
{
  public static final String MIGRATION_COMPLETE = "nexus.upgrade.api.secrets";

  private final ApiKeyStore apiKeyStore;

  private final ApiKeyStore apiKeyStoreV2;

  private final DatabaseCheck databaseCheck;

  private final GlobalKeyValueStore kv;

  private final UserPrincipalsHelper principalsHelper;

  private final Map<String, ApiKeyFactory> apiKeyFactories;

  private final DefaultApiKeyFactory defaultApiKeyFactory;
  
  private final Executor virtualThreadExecutor;

  private volatile boolean secretMigrationComplete = false;

  private volatile boolean onVersion = false;

  @Inject
  public ApiKeyServiceImpl(
      final @Named("v1") ApiKeyStore apiKeyStoreV1,
      final @Named("v2") ApiKeyStore apiKeyStoreV2,
      final DatabaseCheck check,
      final GlobalKeyValueStore kv,
      final UserPrincipalsHelper principalsHelper,
      final Map<String, ApiKeyFactory> apiKeyFactories,
      final DefaultApiKeyFactory defaultApiKeyFactory)
  {
    this.apiKeyStore = checkNotNull(apiKeyStoreV1);
    this.apiKeyStoreV2 = checkNotNull(apiKeyStoreV2);
    this.databaseCheck = checkNotNull(check);
    this.kv = checkNotNull(kv);
    this.principalsHelper = checkNotNull(principalsHelper);
    this.apiKeyFactories = checkNotNull(apiKeyFactories);
    this.defaultApiKeyFactory = checkNotNull(defaultApiKeyFactory);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected void doStart() {
    secretMigrationComplete = kv.getBoolean(MIGRATION_COMPLETE).orElse(false);
  }

  @Override
  public Collection<ApiKey> browse(final String domain) {
    return callBrowse(store -> {
      var future = virtualThreadExecutor.submit(() -> store.browse(domain)
          .stream()
          .map(ApiKey.class::cast)
          .collect(Collectors.toList()));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error browsing API keys for domain \{domain}", e);
        return java.util.Collections.emptyList();
      }
    });
  }

  @Override
  public Collection<ApiKey> browseByCreatedDate(final String domain, final OffsetDateTime date) {
    return callBrowse(store -> {
      var future = virtualThreadExecutor.submit(() -> store.browseByCreatedDate(domain, date)
          .stream()
          .map(ApiKey.class::cast)
          .collect(Collectors.toList()));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error browsing API keys for domain \{domain} by date \{date}", e);
        return java.util.Collections.emptyList();
      }
    });
  }

  @Override
  public Collection<ApiKey> browsePaginated(final String domain, final int page, final int pageSize) {
    return callBrowse(store -> {
      var future = virtualThreadExecutor.submit(() -> store.browsePaginated(domain, page, pageSize)
          .stream()
          .map(ApiKey.class::cast)
          .collect(Collectors.toList()));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error browsing paginated API keys for domain \{domain} (page \{page}, size \{pageSize})", e);
        return java.util.Collections.emptyList();
      }
    });
  }

  @Override
  public int count(final String domain) {
    return callModify(store -> {
      var future = virtualThreadExecutor.submit(() -> store.count(domain));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error counting API keys for domain \{domain}", e);
        return 0;
      }
    });
  }

  @Override
  public char[] createApiKey(final String domain, final PrincipalCollection principals) {
    char[] apiKey = makeApiKey(domain, principals);

    modify(store -> {
      virtualThreadExecutor.execute(() -> store.persistApiKey(domain, principals, apiKey));
    });

    return apiKey;
  }

  @Override
  public int deleteApiKey(final String domain, final PrincipalCollection principals) {
    return callModify(store -> {
      var future = virtualThreadExecutor.submit(() -> store.deleteApiKey(domain, principals));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error deleting API key for domain \{domain}", e);
        return 0;
      }
    });
  }

  @Override
  public int deleteApiKeys(final OffsetDateTime expiration) {
    return callModify(store -> {
      var future = virtualThreadExecutor.submit(() -> store.deleteApiKeys(expiration));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error deleting API keys with expiration \{expiration}", e);
        return 0;
      }
    });
  }

  @Override
  public int deleteApiKeys(final PrincipalCollection principals) {
    checkCancellation();
    return callModify(store -> {
      var future = virtualThreadExecutor.submit(() -> store.deleteApiKeys(principals));
      try {
        return future.get();
      } catch (Exception e) {
        if (principals instanceof SimplePrincipalCollection(var primaryPrincipal, var realmName)) {
          log.error(STR."Error deleting API keys for principal \{primaryPrincipal} from realm \{realmName}", e);
        } else {
          log.error(STR."Error deleting API keys for principals \{principals}", e);
        }
        return 0;
      }
    });
  }

  @Override
  public int deleteApiKeys(final String domain) {
    return callModify(store -> {
      var future = virtualThreadExecutor.submit(() -> store.deleteApiKeys(domain));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error deleting API keys for domain \{domain}", e);
        return 0;
      }
    });
  }

  @Override
  public Optional<ApiKey> getApiKey(final String domain, final PrincipalCollection principals) {
    return find(store -> {
      var future = virtualThreadExecutor.submit(() -> store.getApiKey(domain, principals)
          .map(ApiKey.class::cast));
      try {
        return future.get();
      } catch (Exception e) {
        if (principals instanceof SimplePrincipalCollection(var primaryPrincipal, var realmName)) {
          log.error(STR."Error getting API key for domain \{domain}, principal \{primaryPrincipal} from realm \{realmName}", e);
        } else {
          log.error(STR."Error getting API key for domain \{domain}, principals \{principals}", e);
        }
        return Optional.empty();
      }
    });
  }

  @Override
  public Optional<ApiKey> getApiKeyByToken(final String domain, final char[] apiKey) {
    return find(store -> {
      var future = virtualThreadExecutor.submit(() -> store.getApiKeyByToken(domain, apiKey)
          .map(ApiKey.class::cast));
      try {
        return future.get();
      } catch (Exception e) {
        log.error(STR."Error getting API key by token for domain \{domain}", e);
        return Optional.empty();
      }
    });
  }

  @Override
  public void persistApiKey(
      final String domain,
      final PrincipalCollection principals,
      final char[] apiKey,
      final OffsetDateTime created)
  {
    modify(store -> {
      virtualThreadExecutor.execute(() -> store.persistApiKey(domain, principals, apiKey, created));
    });
  }

  @Override
  public int purgeApiKeys() {
    checkCancellation();

    // Note: we rely on deleteApiKeys to delete from both stores if appropriate
    return StreamSupport.stream(find(ApiKeyStore::browsePrincipals).spliterator(), false)
        .filter(principal -> !userExists(principal))
        .mapToInt(this::deleteApiKeys)
        .sum();
  }

  @Override
  public void updateApiKeyRealm(final ApiKey from, final PrincipalCollection newPrincipal) {
    modify(store -> {
      virtualThreadExecutor.execute(() -> store.updateApiKey((ApiKeyInternal) from, newPrincipal));
    });
  }

  /**
   * An event handler to remove api keys of expired users.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final UserPrincipalsExpired event) {
    final String userId = event.getUserId();
    virtualThreadExecutor.execute(() -> {
      if (userId != null) {
        deleteApiKeys(new SimplePrincipalCollection(userId, event.getSource()));
      }
      else {
        purgeApiKeys();
      }
    });
  }

  private char[] makeApiKey(final String domain, final PrincipalCollection principals) {
    ApiKeyFactory factory = apiKeyFactories.get(domain);
    if (factory != null) {
      return checkNotNull(factory.makeApiKey(principals));
    }
    return defaultApiKeyFactory.makeApiKey(principals);
  }

  private boolean userExists(final PrincipalCollection principals) {
    try {
      principalsHelper.getUserStatus(principals);
    }
    catch (UserNotFoundException e) {
      log.debug(STR."Stale user found: \{principals}", e);
      return false;
    }
    catch (Exception e) {
      log.warn(STR."Failed to lookup user \{principals}", e);
    }
    return true;
  }

  @Subscribe
  public void on(final KeyValueEvent event) {
    if (MIGRATION_COMPLETE.equals(event.getKey())) {
      secretMigrationComplete = (boolean) event.getValue();
    }
  }

  private void modify(final Consumer<ApiKeyStore> consumer) {
    if (isMigrationComplete()) {
      // if migration is complete we only need to update the migrated table
      log.trace(STR."modify new table");
      consumer.accept(apiKeyStoreV2);
    }
    else if (!isOnDBVersion()) {
      // ZDU, if db migration has not begun we can safely modify only the old store
      log.trace(STR."modify old table");
      consumer.accept(apiKeyStore);
    }
    else {
      // DB migration has happened, table migration is in an ambiguous state so we try modifications on both
      log.trace(STR."modify both tables");
      consumer.accept(apiKeyStore);
      consumer.accept(apiKeyStoreV2);
    }
  }

  private int callModify(final ToIntFunction<ApiKeyStore> consumer) {
    if (isMigrationComplete()) {
      // if migration is complete we only need to update the migrated table
      log.trace(STR."callModify new table");
      return consumer.applyAsInt(apiKeyStoreV2);
    }
    else if (!isOnDBVersion()) {
      // ZDU, if db migration has not begun we can safely modify only the old store
      log.trace(STR."callModify old table");
      return consumer.applyAsInt(apiKeyStore);
    }
    // DB migration has happened, table migration is in an ambiguous state so we try modifications on both
    log.trace(STR."callModify both tables");
    return Math.max(consumer.applyAsInt(apiKeyStore), consumer.applyAsInt(apiKeyStoreV2));
  }

  private Collection<ApiKey> callBrowse(final Function<ApiKeyStore, Collection<ApiKey>> fn) {
    if (isMigrationComplete()) {
      log.trace(STR."Browsing new table");
      return fn.apply(apiKeyStoreV2);
    }
    // We'll rely on the modify methods to potentially update both tables
    log.trace(STR."Browsing old table");
    return fn.apply(apiKeyStore);
  }

  private <E> E find(final Function<ApiKeyStore, E> fn) {
    if (isMigrationComplete()) {
      // if migration is complete we only need to lookup the migrated table
      log.trace(STR."find on new table");
      return fn.apply(apiKeyStoreV2);
    }

    return fn.apply(apiKeyStore);
  }

  private boolean isOnDBVersion() {
    onVersion = onVersion || databaseCheck.isAtLeast(SecretsService.SECRETS_MIGRATION_VERSION);

    return onVersion;
  }

  @VisibleForTesting
  boolean isMigrationComplete() {
    return secretMigrationComplete;
  }
}