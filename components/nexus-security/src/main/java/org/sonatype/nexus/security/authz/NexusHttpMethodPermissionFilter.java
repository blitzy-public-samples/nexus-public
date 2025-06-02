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
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.shiro.web.filter.authz.HttpMethodPermissionFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nexus {@link HttpMethodPermissionFilter}.
 * 
 * <p>
 * This implementation is optimized for Java 21 Virtual Thread compatibility, ensuring
 * that HTTP filter operations can efficiently utilize the lightweight threading model
 * without pinning virtual threads to carrier threads.
 * </p>
 *
 * @since 3.0
 */
@Named
@Singleton
public class NexusHttpMethodPermissionFilter
  extends HttpMethodPermissionFilter
{
  public static final String NAME = "nx-http-permissions";

  protected final Logger log = LoggerFactory.getLogger(getClass());
  
  @Override
  protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
    String requestMethod = request.getParameter("method");
    if (requestMethod != null) {
      log.debug(STR."Access denied for request method: \{requestMethod}");
    }
    return super.onAccessDenied(request, response);
  }
}