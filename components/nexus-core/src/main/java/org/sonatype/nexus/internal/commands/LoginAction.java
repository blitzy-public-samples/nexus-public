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
package org.sonatype.nexus.internal.commands;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.security.SecurityHelper;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An action to get a logged in user in the console.
 *
 * @since 3.3
 */
@Named
@Command(name = "login", scope = "nexus", description = "Put a user in context")
public class LoginAction
    implements Action
{
  private static final Logger log = LoggerFactory.getLogger(LoginAction.class);
  
  private final SecurityHelper securityHelper;

  @Option(name = "-u", aliases = {"--username"}, description = "Username to login with", required = true)
  String username;

  @Option(name = "-p", aliases = {"--password"}, description = "Password to login with", required = true)
  String password;

  @Inject
  public LoginAction(final SecurityHelper securityHelper) {
    this.securityHelper = checkNotNull(securityHelper);
  }

  @Override
  public Object execute() throws Exception {
    // Convert password to char array for secure handling
    char[] passwordChars = password.toCharArray();
    
    try {
      // Create token with char array password for better security
      UsernamePasswordToken token = new UsernamePasswordToken(username, passwordChars);
      
      // Log login attempt using String Templates
      log.info(STR."User \{username} attempting to login via console");
      
      // Perform login
      securityHelper.subject().login(token);
      
      // Log successful login
      log.info(STR."User \{username} successfully logged in via console");
      
      return null;
    } 
    catch (Exception e) {
      // Use pattern matching for improved exception handling
      switch (e) {
        case UnknownAccountException uae -> {
          log.warn(STR."Login failed for user \{username}: Unknown account");
          System.err.println("Login failed: Unknown account");
        }
        case IncorrectCredentialsException ice -> {
          log.warn(STR."Login failed for user \{username}: Incorrect credentials");
          System.err.println("Login failed: Incorrect credentials");
        }
        case AuthenticationException ae -> {
          log.warn(STR."Login failed for user \{username}: \{ae.getMessage()}");
          System.err.println(STR."Login failed: \{ae.getMessage()}");
        }
        default -> {
          log.error(STR."Login failed for user \{username}: \{e.getClass().getSimpleName()}");
          System.err.println(STR."Login failed: \{e.getMessage()}");
        }
      }
      throw e;
    }
    finally {
      // Clear password data from memory for security
      if (passwordChars != null) {
        for (int i = 0; i < passwordChars.length; i++) {
          passwordChars[i] = '\0';
        }
      }
    }
  }
}