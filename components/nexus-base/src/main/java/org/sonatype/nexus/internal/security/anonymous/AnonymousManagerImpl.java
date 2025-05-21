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
package org.sonatype.nexus.internal.security.anonymous;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Mutex;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventConsumer;
import org.sonatype.nexus.common.event.EventHelper;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.jmx.reflect.ManagedAttribute;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousConfigurationChangedEvent;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.anonymous.AnonymousPrincipalCollection;

import com.google.common.eventbus.Subscribe;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SubjectContext;
import org.apache.shiro.mgt.DefaultSubjectFactory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link AnonymousManagerImpl}.
 *
 * @since 3.0
 */
@Named
@Singleton
@ManagedObject
public class AnonymousManagerImpl
    extends ComponentSupport
    implements AnonymousManager, EventAware
{
  private final EventManager eventManager;

  private final AnonymousConfigurationStore store;

  private final Provider<AnonymousConfiguration> defaults;

  // Using ReentrantLock instead of Mutex for better Virtual Thread compatibility
  private final Lock lock = new ReentrantLock();

  private AnonymousConfiguration configuration;

  @Inject
  public AnonymousManagerImpl(
      final EventManager eventManager,
      final AnonymousConfigurationStore store,
      @Named("initial") final Provider<AnonymousConfiguration> defaults)
  {
    this.eventManager = checkNotNull(eventManager);
    this.store = checkNotNull(store);
    log.debug(STR."Store: \{store}");
    this.defaults = checkNotNull(defaults);
    log.debug(STR."Defaults: \{defaults}");
  }

  @Override
  public boolean isConfigured() {
    return store.load() != null;
  }

  //
  // Configuration
  //

  /**
   * Load configuration from store, or use defaults.
   */
  private AnonymousConfiguration loadConfiguration() {
    AnonymousConfiguration model = store.load();

    // Use pattern matching to handle the null case more elegantly
    return switch (model) {
      case null -> {
        AnonymousConfiguration defaultModel = defaults.get();
        // default config must not be null
        checkNotNull(defaultModel);

        AnonymousConfiguration newModel = store.newConfiguration();
        newModel.setEnabled(defaultModel.isEnabled());
        newModel.setRealmName(defaultModel.getRealmName());
        newModel.setUserId(defaultModel.getUserId());

        log.info(STR."Using default configuration: \{newModel}");
        yield newModel;
      }
      default -> {
        log.info(STR."Loaded configuration: \{model}");
        yield model;
      }
    };
  }

  /**
   * Return configuration, loading if needed.
   *
   * The result model should be considered _immutable_ unless copied.
   */
  private AnonymousConfiguration getConfigurationInternal() {
    lock.lock();
    try {
      if (configuration == null) {
        configuration = loadConfiguration();
      }
      return configuration;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Return _copy_ of configuration.
   */
  @Override
  public AnonymousConfiguration getConfiguration() {
    return getConfigurationInternal().copy();
  }

  @Override
  public AnonymousConfiguration newConfiguration() {
    return store.newConfiguration();
  }

  @Override
  public void setConfiguration(final AnonymousConfiguration configuration) {
    checkNotNull(configuration);

    AnonymousConfiguration model = configuration.copy();

    log.info(STR."Saving configuration: \{model}");

    lock.lock();
    try {
      if (!EventHelper.isReplicating()) {
        store.save(model);
      }
      this.configuration = model;
    } finally {
      lock.unlock();
    }

    eventManager.post(new AnonymousConfigurationChangedEvent(model));
  }

  //
  // Helpers
  //

  @Override
  @ManagedAttribute
  public boolean isEnabled() {
    return getConfigurationInternal().isEnabled();
  }

  @Override
  public Subject buildSubject() {
    AnonymousConfiguration model = getConfigurationInternal();

    log.trace(STR."Building anonymous subject with user-id: \{model.getUserId()}, realm-name: \{model.getRealmName()}");

    // custom principals to aid with anonymous subject detection
    PrincipalCollection principals = new AnonymousPrincipalCollection(
        model.getUserId(),
        model.getRealmName());

    // Updated for Shiro 2.0.0 compatibility
    // Create a SubjectContext to configure the subject properly
    SubjectContext context = new DefaultSubjectFactory().createSubjectContext();
    context.setPrincipals(principals);
    context.setAuthenticated(false);
    context.setSessionCreationEnabled(false);

    return new Subject.Builder()
        .context(context)
        .buildSubject();
  }

  /**
   * Handles configuration change events with optimized thread handling for Virtual Threads.
   * 
   * @since 3.2
   */
  @Subscribe
  public void onStoreChanged(final AnonymousConfigurationEvent event) {
    // Optimized for Virtual Threads - avoid blocking operations in event handlers
    if (!event.isLocal()) {
      try {
        // Process the event directly without additional thread coordination
        setConfiguration(event.getAnonymousConfiguration());
      }
      catch (Exception e) {
        log.error(STR."Failed to replicate event: \{event}", e);
      }
    }
  }
}