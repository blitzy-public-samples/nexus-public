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
package org.sonatype.nexus.repository.apt.internal;

/**
 * Constants class for APT repository MIME types.
 * 
 * <p>This class is thread-safe and compatible with Java 21 runtime environment.</p>
 *
 * @since 3.17
 */
public final class AptMimeTypes
{
  /**
   * Private constructor to prevent instantiation of this utility class.
   * 
   * @throws IllegalStateException if called
   */
  private AptMimeTypes() {
    throw new IllegalStateException("AptMimeTypes is a utility class and cannot be instantiated");
  }

  /**
   * MIME type for plain text content.
   */
  public static final String TEXT = "text/plain";

  /**
   * MIME type for gzip compressed content.
   */
  public static final String GZIP = "application/gzip";

  /**
   * MIME type for bzip2 compressed content.
   */
  public static final String BZIP = "application/bzip2";

  /**
   * MIME type for XZ compressed content.
   */
  public static final String XZ = "application/x-xz";

  /**
   * MIME type for PGP signatures.
   */
  public static final String SIGNATURE = "application/pgp-signature";

  /**
   * MIME type for PGP public keys.
   */
  public static final String PUBLICKEY = "application/pgp";

  /**
   * MIME type for Debian binary packages.
   */
  public static final String PACKAGE = "application/vnd.debian.binary-package";
}
