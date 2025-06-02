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
package org.sonatype.nexus.security.privilege;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.validation.ConstraintValidatorSupport;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link UniquePrivilegeName} validator.
 *
 * @since 3.0
 */
@Named
public class UniquePrivilegeNameValidator
        implements ConstraintValidator<UniquePrivilegeName, String> {

  private static final Logger log = LoggerFactory.getLogger(UniquePrivilegeNameValidator.class);

  private final SecuritySystem securitySystem;

  @Inject
  public UniquePrivilegeNameValidator(final SecuritySystem securitySystem) {
    this.securitySystem = checkNotNull(securitySystem);
  }

  @Override
  public boolean isValid(final String value, final ConstraintValidatorContext context) {
    log.trace("Validating unique privilege name: {}", value);
    for (Privilege privilege : securitySystem.listPrivileges()) {
      if (value.equals(privilege.getName())) {
        return false;
      }
    }
    return true;
  }
}
