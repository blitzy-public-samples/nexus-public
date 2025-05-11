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
package org.sonatype.nexus.coreui;

import java.util.Map;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;

import org.sonatype.nexus.security.privilege.UniquePrivilegeId;
import org.sonatype.nexus.security.privilege.UniquePrivilegeName;
import org.sonatype.nexus.validation.constraint.NamePatternConstants;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

/**
 * Privilege exchange object.
 * Implemented as a Java Record for immutability and pattern matching support in Java 21.
 */
public record PrivilegeXO(
  @NotBlank(groups = Update.class)
  @UniquePrivilegeId(groups = Create.class)
  String id,

  @NotBlank(groups = Update.class)
  String version,

  @NotBlank
  @Pattern(regexp = NamePatternConstants.REGEX, message = NamePatternConstants.MESSAGE)
  @UniquePrivilegeName(groups = Create.class)
  String name,

  String description,

  @NotBlank
  String type,

  Boolean readOnly,

  @NotEmpty
  Map<String, String> properties,

  String permission
) {
  /**
   * Returns a new PrivilegeXO with the specified id.
   *
   * @param id the id to set
   * @return a new PrivilegeXO with the updated id
   */
  public PrivilegeXO withId(final String id) {
    return new PrivilegeXO(id, this.version, this.name, this.description, this.type, this.readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified version.
   *
   * @param version the version to set
   * @return a new PrivilegeXO with the updated version
   */
  public PrivilegeXO withVersion(final String version) {
    return new PrivilegeXO(this.id, version, this.name, this.description, this.type, this.readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified name.
   *
   * @param name the name to set
   * @return a new PrivilegeXO with the updated name
   */
  public PrivilegeXO withName(final String name) {
    return new PrivilegeXO(this.id, this.version, name, this.description, this.type, this.readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified description.
   *
   * @param description the description to set
   * @return a new PrivilegeXO with the updated description
   */
  public PrivilegeXO withDescription(final String description) {
    return new PrivilegeXO(this.id, this.version, this.name, description, this.type, this.readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified type.
   *
   * @param type the type to set
   * @return a new PrivilegeXO with the updated type
   */
  public PrivilegeXO withType(final String type) {
    return new PrivilegeXO(this.id, this.version, this.name, this.description, type, this.readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified readOnly flag.
   *
   * @param readOnly the readOnly flag to set
   * @return a new PrivilegeXO with the updated readOnly flag
   */
  public PrivilegeXO withReadOnly(final Boolean readOnly) {
    return new PrivilegeXO(this.id, this.version, this.name, this.description, this.type, readOnly, 
        this.properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified properties.
   *
   * @param properties the properties to set
   * @return a new PrivilegeXO with the updated properties
   */
  public PrivilegeXO withProperties(final Map<String, String> properties) {
    return new PrivilegeXO(this.id, this.version, this.name, this.description, this.type, this.readOnly, 
        properties, this.permission);
  }

  /**
   * Returns a new PrivilegeXO with the specified permission.
   *
   * @param permission the permission to set
   * @return a new PrivilegeXO with the updated permission
   */
  public PrivilegeXO withPermission(final String permission) {
    return new PrivilegeXO(this.id, this.version, this.name, this.description, this.type, this.readOnly, 
        this.properties, permission);
  }
}
