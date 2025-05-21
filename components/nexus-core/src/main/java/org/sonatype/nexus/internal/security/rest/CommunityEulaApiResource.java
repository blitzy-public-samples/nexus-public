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
package org.sonatype.nexus.internal.security.rest;

import java.util.Map;
import java.util.Optional;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;
import org.sonatype.nexus.rest.Resource;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static java.lang.StringTemplate.STR;

public class CommunityEulaApiResource
    implements Resource, CommunityEulaApiResourceDoc
{

  private final String EULA_KEY = "nexus.community.eula.accepted";

  private final GlobalKeyValueStore globalKeyValueStore;

  public CommunityEulaApiResource(final GlobalKeyValueStore globalKeyValueStore) {
    this.globalKeyValueStore = globalKeyValueStore;
  }

  @Override
  @RequiresPermissions("nexus:*")
  @RequiresAuthentication
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public EulaStatus getCommunityEulaStatus() {
    Optional<NexusKeyValue> eulaStatusOptional = globalKeyValueStore.getKey(EULA_KEY);
    EulaStatus eulaStatus = new EulaStatus();
    
    // Using Pattern Matching for switch to check EULA acceptance status
    switch (eulaStatusOptional) {
      case Optional<NexusKeyValue> opt when opt.isPresent() -> {
        NexusKeyValue eulaStatusKeyValue = opt.get();
        // Using Pattern Matching for the value object
        switch (eulaStatusKeyValue.value().get("accepted")) {
          case Boolean accepted when accepted != null -> eulaStatus.setAccepted(accepted);
          case null -> eulaStatus.setAccepted(false);
          default -> eulaStatus.setAccepted(false);
        }
      }
      case Optional<NexusKeyValue> empty -> eulaStatus.setAccepted(false);
    }
    
    eulaStatus.setDisclaimer(EulaStatus.EXPECTED_DISCLAIMER);
    return eulaStatus;
  }

  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  @Consumes(MediaType.APPLICATION_JSON)
  public void setEulaAcceptedCE(EulaStatus eulaStatus) {
    // Using Pattern Matching for improved EULA validation logic
    switch (eulaStatus) {
      case EulaStatus status when status.hasExpectedDisclaimer() -> {
        NexusKeyValue kv = new NexusKeyValue();
        kv.setKey(EULA_KEY);
        kv.setType(ValueType.OBJECT);
        kv.setValue(Map.of("accepted", status.isAccepted()));
        globalKeyValueStore.setKey(kv);
      }
      case EulaStatus status -> {
        // Enhanced exception handling with String Templates for improved error reporting
        throw new IllegalArgumentException(STR."Invalid EULA disclaimer: received \{eulaStatus.getDisclaimer() != null ? eulaStatus.getDisclaimer().length() : 0} characters but expected \{EulaStatus.EXPECTED_DISCLAIMER.length()} characters");
      }
    }
  }
}