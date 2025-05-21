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
package org.sonatype.nexus.internal.email;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.mail.Session;
import javax.net.ssl.SSLContext;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventHelper;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailConfigurationChangedEvent;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.ssl.TrustStore;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailConstants;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link EmailManager}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class EmailManagerImpl
    extends ComponentSupport
    implements EmailManager, EventAware
{
  private static final String PASSWORD_PLACEHOLDER = "#~NXRM~PLACEHOLDER~PASSWORD~#";

  public static final String EMAIL_CONFIGURATION_SOURCE = "email-configuration";

  private final EventManager eventManager;

  private final EmailConfigurationStore store;

  private final TrustStore trustStore;

  private final Function<EmailConfiguration, EmailConfiguration> defaults;

  private final ReentrantLock lock = new ReentrantLock();

  private final Provider<CapabilityRegistry> capabilityRegistryProvider;

  private EmailConfiguration configuration;

  private final SecretsService secretsService;

  @Inject
  public EmailManagerImpl(final EventManager eventManager,
                          final EmailConfigurationStore store,
                          final TrustStore trustStore,
                          @Named("initial") final  Function<EmailConfiguration, EmailConfiguration> defaults,
                          final Provider<CapabilityRegistry> capabilityRegistryProvider,
                          final SecretsService secretsService)
  {
    this.eventManager = checkNotNull(eventManager);
    this.store = checkNotNull(store);
    this.trustStore = checkNotNull(trustStore);
    this.defaults = checkNotNull(defaults);
    this.capabilityRegistryProvider = capabilityRegistryProvider;
    this.secretsService = checkNotNull(secretsService);
  }

  /**
   * Load configuration from store, or use defaults.
   */
  private EmailConfiguration loadConfiguration() {
    EmailConfiguration model = store.load();

    // use defaults if no configuration was loaded from the store
    if (model == null) {
      model = defaults.apply(store.newConfiguration());

      // default config must not be null
      checkNotNull(model);

      log.info(STR."Using default configuration: \{model}");
    }
    else {
      log.info(STR."Loaded configuration: \{model}");
    }

    return model;
  }

  /**
   * Return configuration, loading if needed.
   *
   * The result model should be considered _immutable_ unless copied.
   */
  private EmailConfiguration getConfigurationInternal() {
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

  @Override
  public EmailConfiguration getConfiguration() {
    return getConfigurationInternal().copy();
  }

  @Override
  public void setConfiguration(final EmailConfiguration configuration, final String password) {
    checkNotNull(configuration);

    EmailConfiguration model = configuration.copy();

    log.info(STR."Saving configuration: \{model}");

    lock.lock();
    try {
      Secret oldPass = getConfiguration().getPassword();
      Secret newPass = null;
      
      switch (password) {
        case null, "" -> { /* No password provided, do nothing */ }
        case PASSWORD_PLACEHOLDER -> model.setPassword(oldPass);
        case String pwd -> {
          newPass = secretsService.encrypt(EMAIL_CONFIGURATION_SOURCE, pwd.toCharArray(), UserIdHelper.get());
          model.setPassword(newPass);
        }
      }
      
      try {
        store.save(model);
      }
      catch (Exception e) {
        if (Objects.nonNull(newPass)) {
          secretsService.remove(newPass);
        }
        throw e;
      }
      if (Objects.nonNull(oldPass) && model.getPassword() != oldPass) {
        secretsService.remove(oldPass);
      }
      this.configuration = model;
    } finally {
      lock.unlock();
    }

    eventManager.post(new EmailConfigurationChangedEvent(model));
  }

  @Subscribe
  public void onStoreChanged(final EmailConfigurationChanged event) {
    if (EventHelper.isReplicating()) {
      log.debug(STR."Reloading configuration after change by node \{event.getRemoteNodeId()}");
      lock.lock();
      try {
        configuration = loadConfiguration();
      } finally {
        lock.unlock();
      }
    }
  }

  /**
   * Apply server configuration to email.
   */
  @VisibleForTesting
  Email apply(final EmailConfiguration configuration, final Email mail, final String password) throws EmailException {
    mail.setHostName(configuration.getHost());
    mail.setSmtpPort(configuration.getPort());
    if (!Strings.isNullOrEmpty(configuration.getUsername()) || !Strings.isNullOrEmpty(password)) {
      mail.setAuthentication(configuration.getUsername(), password);
    }

    mail.setStartTLSEnabled(configuration.isStartTlsEnabled());
    mail.setStartTLSRequired(configuration.isStartTlsRequired());
    mail.setSSLOnConnect(configuration.isSslOnConnectEnabled());
    mail.setSSLCheckServerIdentity(configuration.isSslCheckServerIdentityEnabled());
    mail.setSslSmtpPort(Integer.toString(configuration.getPort()));

    // default from address
    if (mail.getFromAddress() == null) {
      mail.setFrom(configuration.getFromAddress());
    }

    // apply subject prefix if configured
    String subjectPrefix = configuration.getSubjectPrefix();
    if (subjectPrefix != null) {
      String subject = mail.getSubject();
      mail.setSubject(STR."\{subjectPrefix} \{subject}");
    }

    // do this last (mail properties are set up from the email fields when you get the mail session)
    if (configuration.isNexusTrustStoreEnabled()) {
      SSLContext context = trustStore.getSSLContext();
      Session session = mail.getMailSession();
      Properties properties = session.getProperties();
      properties.remove(EmailConstants.MAIL_SMTP_SOCKET_FACTORY_CLASS);
      properties.put(EmailConstants.MAIL_SMTP_SSL_ENABLE, true);
      properties.put("mail.smtp.ssl.socketFactory", context.getSocketFactory());
    }

    return mail;
  }

  @Override
  public void send(final Email mail) throws EmailException {
    checkNotNull(mail);

    EmailConfiguration model = getConfigurationInternal();
    if (model.isEnabled()) {
      Email prepared = apply(model, mail, getPassword(model));
      sendMailWithVirtualThread(prepared);
    }
    else {
      log.warn("No email enabled but asked to send anyway.");
    }
  }

  @Override
  public void sendVerification(final EmailConfiguration configuration, final String password, final String address)
      throws EmailException
  {
    checkNotNull(configuration);
    Email mail = createVerificationEmail(address);
    if (PASSWORD_PLACEHOLDER.equals(password)) {
      mail = apply(configuration, mail, getPassword(getConfigurationInternal()));
    }
    else {
      mail = apply(configuration, mail, password);
    }
    sendMailWithVirtualThread(mail);
  }

  @Override
  public void sendVerification(final EmailConfiguration configuration, final String address)
      throws EmailException
  {
    checkNotNull(configuration);
    Email mail = createVerificationEmail(address);
    mail = apply(configuration, mail, getPassword(configuration));
    sendMailWithVirtualThread(mail);
  }

  private Email createVerificationEmail(final String address) throws EmailException {
    checkNotNull(address);
    Email mail = new SimpleEmail();
    mail.setSubject("Email configuration verification");
    mail.addTo(address);
    mail.setMsg(constructMessage("Verification successful"));
    return mail;
  }

  private static String getPassword(final EmailConfiguration configuration) {
    return Objects.nonNull(configuration.getPassword()) ? String.valueOf(
        configuration.getPassword().decrypt()) : Strings2.EMPTY;
  }

  @Override
  public EmailConfiguration newConfiguration() {
    return store.newConfiguration();
  }

  @Override
  public String constructMessage(final String message) {
    return capabilityRegistryProvider.get()
        .getAll()
        .stream()
        .map(CapabilityReference::context)
        .filter(context -> context.type().toString().equals("baseurl"))
        .filter(CapabilityContext::isEnabled)
        .map(capabilityContext -> capabilityContext.properties().get("url"))
        .findFirst()
        .map(url -> STR."Message from: \{url}\n\n\{message}")
        .orElse(message);
  }

  /**
   * Sends an email using a virtual thread to avoid blocking the caller thread.
   * This is especially important for SMTP operations which can be slow.
   */
  private void sendMailWithVirtualThread(final Email mail) throws EmailException {
    // Create a semaphore to wait for the virtual thread to complete
    final Semaphore semaphore = new Semaphore(0);
    final EmailException[] exceptionHolder = new EmailException[1];
    
    Thread.startVirtualThread(() -> {
      ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
      try {
        // make sure javax.mail loaded, required for java 11+
        Thread.currentThread().setContextClassLoader(javax.mail.Session.class.getClassLoader());
        mail.send();
      }
      catch (EmailException e) {
        exceptionHolder[0] = e;
      }
      finally {
        Thread.currentThread().setContextClassLoader(currentClassLoader);
        semaphore.release();
      }
    });
    
    try {
      // Wait for the virtual thread to complete
      semaphore.acquire();
      if (exceptionHolder[0] != null) {
        throw exceptionHolder[0];
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EmailException("Email sending was interrupted", e);
    }
  }
}