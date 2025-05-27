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
package org.sonatype.nexus.script.plugin.internal.rest;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.internal.rest.SecurityApiResourceV1;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;

/**
 * Script privilege API resource for RESTEasy 6.2.7.Final with Java 21 Virtual Threads support.
 * 
 * @since 3.26
 */
@Named
@Singleton
@Path(ScriptPrivilegeApiResourceV1.RESOURCE_URI)
public class ScriptPrivilegeApiResourceV1
    extends ScriptPrivilegeApiResource
{
  static final String RESOURCE_URI = SecurityApiResourceV1.V1_RESOURCE_URI + "privileges";
  
  /**
   * Virtual thread executor for handling I/O-bound privilege operations.
   */
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public ScriptPrivilegeApiResourceV1(
      final SecuritySystem securitySystem,
      final Map<String, PrivilegeDescriptor> privilegeDescriptors)
  {
    super(securitySystem, privilegeDescriptors);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Get the virtual thread executor for privilege operations.
   * 
   * @return the virtual thread executor
   */
  @Override
  protected ExecutorService getExecutorService() {
    return virtualThreadExecutor;
  }
}