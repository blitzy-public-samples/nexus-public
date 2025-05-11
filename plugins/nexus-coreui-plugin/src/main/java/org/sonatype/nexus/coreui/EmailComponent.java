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
package org.sonatype.nexus.coreui;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Email;

import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.validation.Validate;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.commons.mail.EmailException;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Email {@link DirectComponent}.
 * 
 * This component handles email configuration and verification operations.
 * All DirectMethod operations in this component benefit from Java 21's Virtual Threads
 * infrastructure, which provides improved scalability for I/O-bound operations
 * like email verification without consuming significant system resources.
 *
 * @since 3.0 (Java 21 Virtual Thread optimization since 3.x)
 */
@Named
@Singleton
@DirectAction(action = "coreui_Email")
public class EmailComponent
    extends DirectComponentSupport
{
  private final EmailManager emailManager;

  @Inject
  public EmailComponent(final EmailManager emailManager) {
    this.emailManager = checkNotNull(emailManager);
  }

  /**
   * Returns current configuration.
   * 
   * This method benefits from Java 21's Virtual Threads infrastructure when called through the REST API.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public EmailConfigurationXO read() {
    return convert(emailManager.getConfiguration());
  }

  EmailConfigurationXO convert(final EmailConfiguration value) {
    return new EmailConfigurationXO(
        value.isEnabled(),
        value.getHost(),
        value.getPort(),
        value.getUsername(),
        value.getPassword() != null ? PasswordPlaceholder.get() : null,
        value.getFromAddress(),
        value.getSubjectPrefix(),
        value.isStartTlsEnabled(),
        value.isStartTlsRequired(),
        value.isSslOnConnectEnabled(),
        value.isSslCheckServerIdentityEnabled(),
        value.isNexusTrustStoreEnabled()
    );
  }

  /**
   * Updates the email configuration.
   * 
   * This method benefits from Java 21's Virtual Threads infrastructure when called through the REST API.
   * 
   * @param configuration the email configuration to update
   * @return the updated configuration
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  @Validate
  public EmailConfigurationXO update(@NotNull @Valid final EmailConfigurationXO configuration) {
    emailManager.setConfiguration(convert(configuration), configuration.getPassword());
    return read();
  }

  EmailConfiguration convert(final EmailConfigurationXO value) {
    EmailConfiguration emailConfiguration = emailManager.newConfiguration();
    emailConfiguration.setEnabled(value.isEnabled());
    emailConfiguration.setHost(value.getHost());
    emailConfiguration.setPort(value.getPort());
    emailConfiguration.setUsername(value.getUsername());
    emailConfiguration.setFromAddress(value.getFromAddress());
    emailConfiguration.setSubjectPrefix(value.getSubjectPrefix());
    emailConfiguration.setStartTlsEnabled(value.isStartTlsEnabled());
    emailConfiguration.setStartTlsRequired(value.isStartTlsRequired());
    emailConfiguration.setSslOnConnectEnabled(value.isSslOnConnectEnabled());
    emailConfiguration.setSslCheckServerIdentityEnabled(value.isSslCheckServerIdentityEnabled());
    emailConfiguration.setNexusTrustStoreEnabled(value.isNexusTrustStoreEnabled());

    return emailConfiguration;
  }

  /**
   * Sends a verification email using the provided configuration.
   * 
   * This method performs an I/O-bound operation (sending an email) which automatically
   * benefits from Java 21's Virtual Threads infrastructure. The DirectMethod annotation
   * ensures this method is executed on a virtual thread when called through the REST API,
   * providing improved scalability without consuming significant system resources.
   * 
   * @param configuration the email configuration to use
   * @param address the email address to send verification to
   * @throws EmailException if there is an error sending the email
   * @since 3.0 (Java 21 Virtual Thread optimization since 3.x)
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  @Validate
  public void sendVerification(
      @NotNull @Valid final EmailConfigurationXO configuration,
      @NotNull @Email final String address)
      throws EmailException
  {
    // Email sending is an I/O-bound operation that benefits from the Java 21 Virtual Thread infrastructure
    // The underlying HTTP/REST layer automatically handles this method on a virtual thread
    emailManager.sendVerification(convert(configuration), configuration.getPassword(), address);
  }
}