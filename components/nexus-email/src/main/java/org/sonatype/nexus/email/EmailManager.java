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
package org.sonatype.nexus.email;

import java.util.concurrent.CompletableFuture;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;

/**
 * Email manager.
 * <p>
 * This interface provides both synchronous and asynchronous methods for email operations.
 * Asynchronous methods leverage Java 21 Virtual Threads for improved concurrency and scalability,
 * particularly for I/O-bound operations like sending emails.
 * <p>
 * Thread Safety: Implementations of this interface must be thread-safe and support concurrent
 * operations from both platform threads and virtual threads.
 *
 * @since 3.0
 */
public interface EmailManager
{
  /**
   * Returns copy of current email configuration.
   */
  EmailConfiguration getConfiguration();

  /**
   * Installs new email configuration.
   */
  void setConfiguration(EmailConfiguration configuration, String password);

  /**
   * Send an email synchronously.
   * <p>
   * This method blocks until the email is sent. For non-blocking operations,
   * consider using {@link #sendAsync(Email)} instead, especially when sending
   * emails from request handling code paths.
   */
  void send(Email mail) throws EmailException;

  /**
   * Send an email asynchronously using Java 21 Virtual Threads.
   * <p>
   * This method returns immediately and performs the email sending operation on a virtual thread,
   * which is more efficient for I/O-bound operations. This approach prevents blocking request threads
   * and improves overall system responsiveness under load.
   * <p>
   * Prefer this method over {@link #send(Email)} when:
   * <ul>
   *   <li>Sending emails from request handling code paths</li>
   *   <li>Sending multiple emails concurrently</li>
   *   <li>Email delivery is not required to complete before returning to the caller</li>
   * </ul>
   *
   * @param mail the email to send
   * @return a CompletableFuture that completes when the email is sent or exceptionally when an error occurs
   * @since 3.60
   */
  CompletableFuture<Void> sendAsync(Email mail);

  /**
   * Send verification email to given address using the provided password.
   * <p>
   * This method blocks until the email is sent. For non-blocking operations,
   * consider using {@link #sendVerificationAsync(EmailConfiguration, String, String)} instead.
   */
  void sendVerification(EmailConfiguration configuration, String password, String address) throws EmailException;

  /**
   * Send verification email to given address using the provided password asynchronously.
   * <p>
   * This method returns immediately and performs the email sending operation on a virtual thread,
   * which is more efficient for I/O-bound operations.
   *
   * @param configuration the email configuration to use
   * @param password the password for the email configuration
   * @param address the recipient email address
   * @return a CompletableFuture that completes when the email is sent or exceptionally when an error occurs
   * @since 3.60
   */
  CompletableFuture<Void> sendVerificationAsync(EmailConfiguration configuration, String password, String address);

  /**
   * Send verification email to given address using the existing password.
   * <p>
   * This method blocks until the email is sent. For non-blocking operations,
   * consider using {@link #sendVerificationAsync(EmailConfiguration, String)} instead.
   */
  void sendVerification(EmailConfiguration configuration, String address) throws EmailException;

  /**
   * Send verification email to given address using the existing password asynchronously.
   * <p>
   * This method returns immediately and performs the email sending operation on a virtual thread,
   * which is more efficient for I/O-bound operations.
   *
   * @param configuration the email configuration to use
   * @param address the recipient email address
   * @return a CompletableFuture that completes when the email is sent or exceptionally when an error occurs
   * @since 3.60
   */
  CompletableFuture<Void> sendVerificationAsync(EmailConfiguration configuration, String address);

  /**
   * Create a new and empty {@link EmailConfiguration}
   *
   * @since 3.20
   */
  EmailConfiguration newConfiguration();

  /**
   * Construct a message synchronously.
   * <p>
   * This method blocks until the message is constructed. For non-blocking operations,
   * consider using {@link #constructMessageAsync(String)} instead.
   *
   * @param message the message to construct
   * @return the constructed message
   */
  String constructMessage(String message);

  /**
   * Construct a message asynchronously using Java 21 Virtual Threads.
   * <p>
   * This method returns immediately and performs the message construction on a virtual thread.
   * This is particularly useful when message construction involves template processing or other
   * potentially time-consuming operations.
   *
   * @param message the message to construct
   * @return a CompletableFuture that completes with the constructed message or exceptionally when an error occurs
   * @since 3.60
   */
  CompletableFuture<String> constructMessageAsync(String message);
}
