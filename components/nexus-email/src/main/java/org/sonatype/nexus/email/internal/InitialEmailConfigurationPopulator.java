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
package org.sonatype.nexus.email.internal;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.email.EmailConfiguration;

/**
 * Initial {@link EmailConfiguration} populator.
 * Fills in the object with defaults
 *
 * <p>This implementation is compatible with Java 21 and supports execution in both platform and
 * virtual thread contexts. It can be safely used in asynchronous operations, including those leveraging
 * Java 21's Virtual Threads for high-concurrency scenarios.</p>
 *
 * <p>The implementation is stateless and thread-safe, making it suitable for concurrent access
 * patterns in both synchronous and asynchronous execution environments.</p>
 *
 * @since 3.0
 */
@Named("initial")
@Singleton
public class InitialEmailConfigurationPopulator
    implements Function<EmailConfiguration, EmailConfiguration>
{
  /**
   * Applies default configuration values to the provided {@link EmailConfiguration} instance.
   * This method is thread-safe and can be executed in both platform and virtual thread contexts.
   *
   * @param configuration the email configuration to populate with defaults
   * @return the populated configuration instance (same instance as the input parameter)
   */
  @Override
  public EmailConfiguration apply(final EmailConfiguration configuration) {
    configuration.setEnabled(false);
    configuration.setHost("localhost");
    configuration.setPort(25);
    configuration.setFromAddress("nexus@example.org");
    return configuration;
  }
  
  /**
   * Asynchronously applies default configuration values to the provided {@link EmailConfiguration} instance.
   * This method is designed for use with Java 21 Virtual Threads and asynchronous processing patterns.
   *
   * @param configuration the email configuration to populate with defaults
   * @return a CompletableFuture containing the populated configuration instance
   */
  public CompletableFuture<EmailConfiguration> applyAsync(final EmailConfiguration configuration) {
    return CompletableFuture.supplyAsync(() -> apply(configuration));
  }
}