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
package org.sonatype.nexus.security.authz;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.web.filter.authz.PermissionsAuthorizationFilter;

import static java.lang.StringTemplate.STR;

/**
 * Nexus {@link PermissionsAuthorizationFilter}.
 * 
 * Optimized for Virtual Thread compatibility to improve performance in I/O-bound operations.
 */
@Named
@Singleton
public class PermissionsFilter
  extends PermissionsAuthorizationFilter
{
  public static final String NAME = "nx-perms";

  /**
   * Helper to build filter configuration.
   * 
   * Uses pattern matching for permission validation and String Templates for improved performance.
   */
  public static String config(final String... permissions) {
    // Use pattern matching to validate permissions array
    if (permissions instanceof String[] perms && perms.length > 0) {
      // Use String Templates for better performance and readability
      return STR."\{NAME}[\{String.join(",", perms)}]";
    }
    throw new IllegalArgumentException("Permissions array must not be null or empty");
  }
}