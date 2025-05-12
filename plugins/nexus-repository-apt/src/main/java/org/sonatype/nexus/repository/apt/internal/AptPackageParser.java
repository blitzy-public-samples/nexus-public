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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFileParser;
import org.sonatype.nexus.repository.apt.internal.debian.PackageInfo;
import org.sonatype.nexus.repository.apt.internal.org.apache.commons.compress.archivers.ar.ArArchiveInputStream;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.io.input.CloseShieldInputStream;

/**
 * Parser for APT package files.
 * 
 * @since 3.17
 * @see <a href="https://openjdk.org/jeps/444">JEP 444: Virtual Threads</a>
 * @see <a href="https://openjdk.org/jeps/441">JEP 441: Pattern Matching for switch</a>
 */
public class AptPackageParser
{
  private AptPackageParser() {
    throw new IllegalAccessError("Utility class");
  }

  /**
   * Parses package information from the given input stream supplier.
   *
   * @param supplier the input stream supplier
   * @return the package information
   * @throws IOException if an I/O error occurs
   */
  public static PackageInfo parsePackageInfo(final InputStreamSupplier supplier) throws IOException {
    ControlFile controlFile = parsePackageInternal(supplier);
    if (controlFile == null) {
      throw new IOException("Invalid debian package: no control file");
    }
    return new PackageInfo(controlFile);
  }

  /**
   * Asynchronously parses package information using a virtual thread.
   * This method leverages Java 21 Virtual Threads for improved I/O performance.
   *
   * @param supplier the input stream supplier
   * @return a Future containing the package information
   */
  public static Future<PackageInfo> parsePackageInfoAsync(final InputStreamSupplier supplier) {
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> parsePackageInfo(supplier));
  }

  private static ControlFile parsePackageInternal(final InputStreamSupplier supplier) throws IOException {
    try (ArArchiveInputStream is = new ArArchiveInputStream(supplier.get())) {
      ControlFile control = null;
      ArchiveEntry debEntry;
      while ((debEntry = is.getNextEntry()) != null) {
        String entryName = debEntry.getName();
        InputStream controlStream = switch (entryName) {
          case "control.tar" -> new CloseShieldInputStream(is);
          case "control.tar.gz" -> new GzipCompressorInputStream(new CloseShieldInputStream(is));
          case "control.tar.xz" -> new XZCompressorInputStream(new CloseShieldInputStream(is));
          case "control.tar.zst" -> new ZstdCompressorInputStream(new CloseShieldInputStream(is));
          default -> null;
        };

        if (controlStream != null) {
          try (TarArchiveInputStream controlTarStream = new TarArchiveInputStream(controlStream)) {
            ArchiveEntry tarEntry;
            while ((tarEntry = controlTarStream.getNextEntry()) != null) {
              String tarEntryName = tarEntry.getName();
              if ("control".equals(tarEntryName) || "./control".equals(tarEntryName)) {
                control = new ControlFileParser().parseControlFile(controlTarStream);
              }
            }
          }
        }
      }
      return control;
    }
  }
}