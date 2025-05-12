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
 * Constants for APT package file names.
 * 
 * @since 3.17
 * @since 3.60 Updated for Java 21 compatibility using sealed interface pattern
 */
public sealed interface PackageName permits PackageName.Impl {
  /**
   * Uncompressed packages file name.
   */
  String PACKAGES = "Packages";

  /**
   * GZip compressed packages file name.
   */
  String PACKAGES_GZ = "Packages.gz";

  /**
   * BZip2 compressed packages file name.
   */
  String PACKAGES_BZ2 = "Packages.bz2";

  /**
   * XZ compressed packages file name.
   */
  String PACKAGES_XZ = "Packages.xz";
  
  /**
   * Private implementation class to seal the interface.
   * This prevents other classes from implementing this interface.
   */
  final class Impl implements PackageName {
    private Impl() {
      // Prevent instantiation
    }
  }
}