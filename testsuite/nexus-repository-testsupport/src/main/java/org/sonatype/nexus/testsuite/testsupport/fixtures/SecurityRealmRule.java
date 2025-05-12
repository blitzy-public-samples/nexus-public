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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.nexus.security.realm.RealmManager;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension for managing security realms during tests.
 * <p>
 * This extension saves the current security realm configuration before each test
 * and restores it after each test, allowing tests to safely modify the security realms
 * without affecting other tests.
 * <p>
 * Compatible with Java 21 and JUnit Jupiter 5.10.1.
 */
@Named
@Singleton
public class SecurityRealmRule
    implements BeforeEachCallback, AfterEachCallback
{
  private static final String NAMESPACE = SecurityRealmRule.class.getName();
  private static final String CONFIGURED_REALMS = "configuredRealms";
  
  final Provider<RealmManager> realmManagerProvider;

  /**
   * Constructor with provider for lazy initialization.
   *
   * @param realmManagerProvider the provider for RealmManager
   */
  public SecurityRealmRule(final Provider<RealmManager> realmManagerProvider) {
    this.realmManagerProvider = realmManagerProvider;
  }

  /**
   * Constructor with direct RealmManager injection.
   *
   * @param realmManager the RealmManager instance
   */
  @Inject
  public SecurityRealmRule(final RealmManager realmManager) {
    this.realmManagerProvider = () -> realmManager;
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    List<String> configuredRealms = realmManagerProvider.get().getConfiguredRealmIds();
    getStore(context).put(CONFIGURED_REALMS, configuredRealms);
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    List<String> configuredRealms = getStore(context).get(CONFIGURED_REALMS, List.class);
    if (configuredRealms != null) {
      realmManagerProvider.get().setConfiguredRealmIds(configuredRealms);
    }
  }

  /**
   * Adds a security realm to the active configuration.
   *
   * @param realm the realm ID to enable
   */
  public void addSecurityRealm(final String realm) {
    realmManagerProvider.get().enableRealm(realm);
  }

  /**
   * Removes a security realm from the active configuration.
   *
   * @param realm the realm ID to disable
   */
  public void removeSecurityRealm(final String realm) {
    realmManagerProvider.get().disableRealm(realm);
  }
  
  private ExtensionContext.Store getStore(final ExtensionContext context) {
    return context.getStore(ExtensionContext.Namespace.create(NAMESPACE));
  }
}
