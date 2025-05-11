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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.rapture.PasswordPlaceholder;

import static org.mockito.Mockito.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link EmailComponent} using JUnit Jupiter (JUnit 5) with Mockito 4.11.0 extension.
 * Updated for Java 21 compatibility.
 */
@ExtendWith(MockitoExtension.class)
public class EmailComponentTest
    extends TestSupport
{
  private static final String ADDRESS = "you@somewhere.com";

  @Mock
  private EmailManager emailManager;

  @InjectMocks
  private EmailComponent underTest;

  private EmailConfiguration emailConfiguration;

  @BeforeEach
  public void setUp() {
    emailConfiguration = mock(EmailConfiguration.class);
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);
    when(emailConfiguration.isEnabled()).thenReturn(false);
    when(emailConfiguration.getHost()).thenReturn("localhost");
    when(emailConfiguration.getPort()).thenReturn(25);
    when(emailConfiguration.getFromAddress()).thenReturn("nexus@example.org");
    when(emailConfiguration.getUsername()).thenReturn("foo");
    when(emailConfiguration.isStartTlsEnabled()).thenReturn(true);
    when(emailConfiguration.isStartTlsRequired()).thenReturn(true);
    when(emailConfiguration.isSslOnConnectEnabled()).thenReturn(true);
    when(emailConfiguration.getSubjectPrefix()).thenReturn("prefix");
  }

  /**
   * Tests that reading the configuration returns the current configuration with a password placeholder
   * when a password is set.
   */
  @Test
  public void readReturnsCurrentConfigurationWithPasswordPlaceHolder() {
    when(emailConfiguration.getPassword()).thenReturn(mock(Secret.class));

    EmailConfigurationXO actualConfig = underTest.read();

    assertThat(actualConfig, is(notNullValue()));
    assertThat(actualConfig.isEnabled(), is(false));
    assertThat(actualConfig.getHost(), is("localhost"));
    assertThat(actualConfig.getPort(), is(25));
    assertThat(actualConfig.getUsername(), is("foo"));
    assertThat(actualConfig.getFromAddress(), is("nexus@example.org"));
    assertThat(actualConfig.getPassword(), is(PasswordPlaceholder.get()));
    assertThat(actualConfig.isStartTlsEnabled(), is(true));
    assertThat(actualConfig.isStartTlsRequired(), is(true));
    assertThat(actualConfig.isSslOnConnectEnabled(), is(true));
    assertThat(actualConfig.getSubjectPrefix(), is("prefix"));
    assertThat(actualConfig.isNexusTrustStoreEnabled(), is(false));
    assertThat(actualConfig.isSslCheckServerIdentityEnabled(), is(false));
  }

  /**
   * Tests that reading the configuration returns the current configuration with a null password
   * when no password is set.
   */
  @Test
  public void readReturnsCurrentConfigurationWithEmptyPasswordPlaceHolder() {
    when(emailConfiguration.getPassword()).thenReturn(null);

    EmailConfigurationXO actualConfig = underTest.read();

    assertThat(actualConfig, is(notNullValue()));
    assertThat(actualConfig.isEnabled(), is(false));
    assertThat(actualConfig.getHost(), is("localhost"));
    assertThat(actualConfig.getPort(), is(25));
    assertThat(actualConfig.getUsername(), is("foo"));
    assertThat(actualConfig.getFromAddress(), is("nexus@example.org"));
    assertThat(actualConfig.getPassword(), is(nullValue()));
    assertThat(actualConfig.isStartTlsEnabled(), is(true));
    assertThat(actualConfig.isStartTlsRequired(), is(true));
    assertThat(actualConfig.isSslOnConnectEnabled(), is(true));
    assertThat(actualConfig.getSubjectPrefix(), is("prefix"));
    assertThat(actualConfig.isNexusTrustStoreEnabled(), is(false));
    assertThat(actualConfig.isSslCheckServerIdentityEnabled(), is(false));
  }

  /**
   * Tests that updating the configuration saves it correctly.
   */
  @Test
  public void updateSavesConfiguration() {
    when(emailManager.newConfiguration()).thenReturn(emailConfiguration);

    underTest.update(getConfigurationXO("baz"));

    verify(emailManager).setConfiguration(emailConfiguration, "baz");
    verify(emailManager).getConfiguration();
  }

  /**
   * Tests that sending a verification email works correctly.
   */
  @Test
  public void sendVerification() throws Exception {
    EmailConfigurationXO formCredentials = getConfigurationXO("baz");
    EmailConfiguration emailConfig = mock(EmailConfiguration.class);
    when(emailManager.newConfiguration()).thenReturn(emailConfig);

    underTest.sendVerification(formCredentials, ADDRESS);

    verify(emailManager).sendVerification(emailConfig, "baz", ADDRESS);
  }

  /**
   * Helper method to create an {@link EmailConfigurationXO} with the given password.
   * 
   * @param password the password to set in the configuration
   * @return a new email configuration object
   */
  private static EmailConfigurationXO getConfigurationXO(final String password) {
    return new EmailConfigurationXO(
        false, "localhost", 25,
        "foo", password, "nexus@example.org",
        null, false, false,
        false, false, false
    );
  }
}
