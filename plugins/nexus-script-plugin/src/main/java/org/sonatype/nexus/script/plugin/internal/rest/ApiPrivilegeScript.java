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

import java.util.Collection;
import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.script.plugin.internal.security.ScriptPrivilegeDescriptor;
import org.sonatype.nexus.security.internal.rest.NexusSecurityApiConstants;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.rest.ApiPrivilegeWithActions;
import org.sonatype.nexus.security.privilege.rest.PrivilegeAction;

import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotBlank;

/**
 * Script privilege API model for REST operations.
 * 
 * @since 3.19
 */
public class ApiPrivilegeScript
    extends ApiPrivilegeWithActions
{
  public static final String SCRIPT_KEY = "name";

  @NotBlank
  @ApiModelProperty(NexusSecurityApiConstants.PRIVILEGE_SCRIPT_DESCRIPTION)
  private String scriptName;

  /**
   * Default constructor for Jackson deserialization
   */
  private ApiPrivilegeScript() {
    super(ScriptPrivilegeDescriptor.TYPE);
  }

  /**
   * Full constructor for creating a new script privilege
   */
  public ApiPrivilegeScript(final String name,
                            final String description,
                            final boolean readOnly,
                            final String scriptName,
                            final Collection<PrivilegeAction> actions)
  {
    super(ScriptPrivilegeDescriptor.TYPE, name, description, readOnly, actions);
    this.scriptName = scriptName;
  }

  /**
   * Constructor that converts from a domain Privilege object
   */
  public ApiPrivilegeScript(final Privilege privilege) {
    super(privilege);
    // Using pattern matching to extract property from privilege
    switch (privilege) {
      case Privilege p when p != null -> scriptName = p.getPrivilegeProperty(SCRIPT_KEY);
      default -> throw new IllegalArgumentException(STR."Invalid privilege: \{privilege}");
    }
  }

  /**
   * Sets the script name for this privilege
   */
  public void setScriptName(final String scriptName) {
    this.scriptName = scriptName;
  }

  /**
   * Gets the script name for this privilege
   */
  public String getScriptName() {
    return scriptName;
  }

  @Override
  protected Privilege doAsPrivilege(final Privilege privilege) {
    super.doAsPrivilege(privilege);
    // Using pattern matching to set property on privilege
    switch (privilege) {
      case Privilege p when p != null -> p.addProperty(SCRIPT_KEY, scriptName);
      default -> throw new IllegalArgumentException(STR."Cannot add property to null privilege");
    }
    return privilege;
  }

  @Override
  protected String doAsActionString() {
    // Using Java 21 String Templates for more readable string formatting
    String actions = toBreadRunActionString();
    return STR."Script actions: \{actions}";
  }
}