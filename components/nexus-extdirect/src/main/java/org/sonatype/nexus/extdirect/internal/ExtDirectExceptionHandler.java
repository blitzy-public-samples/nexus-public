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
package org.sonatype.nexus.extdirect.internal;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

import org.sonatype.nexus.common.app.FrozenException;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.extdirect.model.Response;
import org.sonatype.nexus.rest.ValidationErrorsException;

import com.softwarementors.extjs.djn.api.RegisteredMethod;
import org.apache.commons.collections4.ListUtils;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authz.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;
import static org.sonatype.nexus.extdirect.model.Responses.error;
import static org.sonatype.nexus.extdirect.model.Responses.invalid;

/**
 * Exception handler for ExtDirect requests.
 * 
 * @since 3.15
 */
@Named
@ManagedLifecycle(phase = SERVICES)
@Singleton
public class ExtDirectExceptionHandler
{
  private static final Logger log = LoggerFactory.getLogger(ExtDirectExceptionHandler.class);

  private static final List<Class<? extends RuntimeException>> SUPPRESSED_EXCEPTIONS = ListUtils.unmodifiableList(
      Arrays.asList(UnauthenticatedException.class, AuthenticationException.class, ValidationErrorsException.class));

  /**
   * Handle exceptions from ExtDirect requests.
   *
   * @param method the registered method that threw the exception
   * @param e the exception that was thrown
   * @return an appropriate response based on the exception type
   */
  public Response handleException(final RegisteredMethod method, final Throwable e) {
    // debug logging for sanity (without stacktrace for suppressed exception)
    log.debug(STR."Failed to invoke action method: \{method.getFullName()}, java-method: \{method.getFullJavaMethodName()}, exception message: \{e.getMessage()}",
        isSuppressedException(e) ? null : e);

    // Use pattern matching with switch to handle different exception types
    return switch (e) {
      // Handle validation message responses which have contents
      case ConstraintViolationException cve when cve.getConstraintViolations() != null && !cve.getConstraintViolations().isEmpty() -> {
        yield invalid(cve);
      }
      
      // Handle frozen repository exceptions
      case FrozenException fe -> {
        yield error(new Exception("Nexus Repository Manager is in read-only mode"));
      }
      
      case Throwable t when t.getCause() instanceof FrozenException -> {
        yield error(new Exception("Nexus Repository Manager is in read-only mode"));
      }
      
      // Handle database-related exceptions
      case SQLException se -> {
        logErrorIfNotSuppressed(method, e);
        yield error(new Exception("A database error occurred"));
      }
      
      // Handle connection-related exceptions
      case HttpHostConnectException hce -> {
        logErrorIfNotSuppressed(method, e);
        yield error(new Exception("Connection unsuccessful."));
      }
      
      // Handle all other exceptions with pattern matching on class name
      case Throwable t -> {
        logErrorIfNotSuppressed(method, e);
        String exceptionName = t.getClass().getName();
        
        if (exceptionName.contains("org.apache.ibatis") || exceptionName.contains("org.sonatype.nexus.datastore")) {
          yield error(new Exception("A database error occurred"));
        } else if (exceptionName.contains("com.sonatype.insight.rm.rest.HttpException")) {
          yield error(new Exception("Connection unsuccessful."));
        } else {
          yield error(e);
        }
      }
    };
  }

  /**
   * Log error for non-suppressed exceptions.
   */
  private void logErrorIfNotSuppressed(final RegisteredMethod method, final Throwable e) {
    if (!isSuppressedException(e)) {
      log.error(STR."Failed to invoke action method: \{method.getFullName()}, java-method: \{method.getFullJavaMethodName()}", e);
    }
  }

  /**
   * Check if the exception or its cause is in the list of suppressed exceptions.
   */
  private boolean isSuppressedException(final Throwable e) {
    return SUPPRESSED_EXCEPTIONS.stream().anyMatch(ex -> switch(e) {
      case Throwable t when ex.isInstance(t) -> true;
      case Throwable t when t.getCause() != null && ex.isInstance(t.getCause()) -> true;
      default -> false;
    });
  }
}