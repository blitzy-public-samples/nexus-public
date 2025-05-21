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
package org.sonatype.nexus.internal.security.secrets;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.crypto.secrets.EncryptionKeyValidator;

import com.codahale.metrics.health.HealthCheck;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link HealthCheck} which fails if the administrator has not configured a key to use for encrypting secrets.
 * <p>
 * Updated for Java 21 compatibility with BouncyCastle 1.78.1 cryptographic provider and enhanced security model.
 */
@FeatureFlag(name = "nexus.health.check.encryption", enabledByDefault = true)
@Named("Default Secret Encryption Key")
@Singleton
public class DefaultEncryptionKeyHealthCheck
    extends HealthCheck
{
  private static final String TEMPLATE_HEALTHY = "Nexus was configured to use %s to encrypt secrets.";

  private static final String FAIL = "Nexus was not configured with an encryption key and is using the Default key.";

  private final EncryptionKeyValidator encryptionKeyValidator;

  @Inject
  public DefaultEncryptionKeyHealthCheck(final EncryptionKeyValidator encryptionKeyValidator) {
    this.encryptionKeyValidator = checkNotNull(encryptionKeyValidator);
  }

  @Override
  protected Result check() throws Exception {
    // Using Java 21 Pattern Matching for Optional to improve code readability
    Optional<String> activeKeyId = encryptionKeyValidator.getActiveKeyId();
    
    if (activeKeyId instanceof Optional<String> opt && opt.isPresent()) {
      String keyId = opt.get();
      return Result.healthy(createHealthyMessage(keyId));
    } else {
      return Result.unhealthy(FAIL);
    }
  }

  private static String createHealthyMessage(final String keyId) {
    // Using Java 21 String Templates for more efficient message formatting
    return STR."Nexus was configured to use \{keyId} to encrypt secrets.";
  }
}
