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
package org.sonatype.nexus.formfields;

/**
 * Marker interface for form fields whose value should be stored encrypted.
 * 
 * <p>
 * When a form field implements this interface, the system will use the configured
 * cryptographic providers in Java 21 to securely encrypt the field's value before storage.
 * Java 21 includes enhanced security features and updated cryptographic providers that
 * ensure robust encryption of sensitive data.
 * </p>
 * 
 * <p>
 * The actual encryption implementation is handled by the Nexus security subsystem,
 * which leverages Java 21's Java Cryptography Architecture (JCA) and its provider
 * framework for cryptographic operations. This ensures compatibility with the latest
 * security standards and algorithms available in Java 21.
 * </p>
 *
 * @since 2.7
 */
public interface Encrypted
{
  // empty
}