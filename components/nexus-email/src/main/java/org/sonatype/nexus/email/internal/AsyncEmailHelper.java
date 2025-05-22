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
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;

/**
 * Helper class that provides asynchronous email operations using Java 21 Virtual Threads.
 * Wraps the {@link EmailManager} to offer non-blocking email sending methods that return
 * {@link CompletableFuture}, allowing applications to send emails without blocking the calling thread.
 *
 * @since 3.60
 */
@Named
@Singleton
public class AsyncEmailHelper
{
  private final EmailManager emailManager;

  @Inject
  public AsyncEmailHelper(final EmailManager emailManager) {
    this.emailManager = emailManager;
  }

  /**
   * Send an email asynchronously using a virtual thread.
   *
   * @param mail the email to send
   * @return a CompletableFuture that completes when the email is sent or completes exceptionally if an error occurs
   */
  public CompletableFuture<Void> sendAsync(final Email mail) {
    return CompletableFuture.runAsync(() -> {
      try {
        emailManager.send(mail);
      }
      catch (EmailException e) {
        throw new RuntimeException("Failed to send email asynchronously", e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Send verification email to given address using the provided password asynchronously.
   *
   * @param configuration the email configuration to use
   * @param password the password for the email configuration
   * @param address the email address to send verification to
   * @return a CompletableFuture that completes when the verification email is sent or completes exceptionally if an error occurs
   */
  public CompletableFuture<Void> sendVerificationAsync(final EmailConfiguration configuration, 
                                                     final String password, 
                                                     final String address) {
    return CompletableFuture.runAsync(() -> {
      try {
        emailManager.sendVerification(configuration, password, address);
      }
      catch (EmailException e) {
        throw new RuntimeException("Failed to send verification email asynchronously", e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Send verification email to given address using the existing password asynchronously.
   *
   * @param configuration the email configuration to use
   * @param address the email address to send verification to
   * @return a CompletableFuture that completes when the verification email is sent or completes exceptionally if an error occurs
   */
  public CompletableFuture<Void> sendVerificationAsync(final EmailConfiguration configuration, 
                                                     final String address) {
    return CompletableFuture.runAsync(() -> {
      try {
        emailManager.sendVerification(configuration, address);
      }
      catch (EmailException e) {
        throw new RuntimeException("Failed to send verification email asynchronously", e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Constructs a message asynchronously using the email manager.
   *
   * @param message the message to construct
   * @return a CompletableFuture that completes with the constructed message or completes exceptionally if an error occurs
   */
  public CompletableFuture<String> constructMessageAsync(final String message) {
    return CompletableFuture.supplyAsync(
        () -> emailManager.constructMessage(message),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Returns a copy of current email configuration asynchronously.
   *
   * @return a CompletableFuture that completes with the current email configuration
   */
  public CompletableFuture<EmailConfiguration> getConfigurationAsync() {
    return CompletableFuture.supplyAsync(
        () -> emailManager.getConfiguration(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Creates a new and empty {@link EmailConfiguration} asynchronously.
   *
   * @return a CompletableFuture that completes with a new empty email configuration
   */
  public CompletableFuture<EmailConfiguration> newConfigurationAsync() {
    return CompletableFuture.supplyAsync(
        () -> emailManager.newConfiguration(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * Installs new email configuration asynchronously.
   *
   * @param configuration the new email configuration to install
   * @param password the password for the email configuration
   * @return a CompletableFuture that completes when the configuration is installed
   */
  public CompletableFuture<Void> setConfigurationAsync(final EmailConfiguration configuration, 
                                                     final String password) {
    return CompletableFuture.runAsync(() -> {
      emailManager.setConfiguration(configuration, password);
    }, Executors.newVirtualThreadPerTaskExecutor());
  }
}