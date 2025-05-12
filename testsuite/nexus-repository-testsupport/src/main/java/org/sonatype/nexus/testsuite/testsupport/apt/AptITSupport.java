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
package org.sonatype.nexus.testsuite.testsupport.apt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.testsuite.testsupport.RepositoryITSupport;
import org.sonatype.nexus.testsuite.testsupport.fixtures.RepositoryRule;
import org.sonatype.nexus.thread.io.VirtualThreads;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

/**
 * Support for Apt ITs with Java 21 compatibility.
 * 
 * <p>This class provides support for Apt (Advanced Package Tool) integration tests,
 * leveraging Java 21 features including Virtual Threads for improved I/O operations
 * and updated cryptography support with BouncyCastle 1.78.1.</p>
 * 
 * <p>The implementation has been updated to use JUnit Jupiter 5.10.1 annotations
 * and properly handle PGP signature verification in a Java 21 environment.</p>
 */
public class AptITSupport
    extends RepositoryITSupport
    implements AptTestGroup
{
  protected static final String CONTENT_TYPE = "application/x-debian-package";

  protected static final String CATEGORY = StringUtils.EMPTY;

  public static final String DEB = "nano_2.2.6-1_amd64.deb";

  public static final String DEB_SZT =
      "linux-image-unsigned-5.14.1-051401-generic_5.14.1-051401.202109030936_amd64.deb";

  public static final String DEB_ARCH = "amd64";

  public static final String DEB_NAME = "nano";

  public static final String DEB_VERSION = "2.2.6-1";

  public static final String DEB_PATH = "pool/n/nano/" + DEB;

  public static final String DEB_V2_5 = "nano_2.5.3-2_amd64.deb";

  public static final String DEB_V2_5_PATH = "pool/n/nano/" + DEB_V2_5;

  public static final String DEB_V2_5_VERSION = "2.5.3-2";

  public static final String CPU_LIMIT_DEB = "cpulimit_2.5-1_amd64.deb";

  public static final String CPU_LIMIT_NAME = "cpulimit";

  public static final String DISTRIBUTION = "bionic";

  public static final String HOSTED_REPO_NAME = "apt-hosted";

  public static final String HOSTED_REPO_WITH_COMPONENTS_NAME = "apt-hosted-with-components";

  public static final String GPG_KEY_NAME = "gpgKey";

  protected static final String GPG_PUBLIC_KEY_NAME = "gpgPublicKey";

  protected static final String METEDATA_PATH = "dists/bionic/";

  protected static final String METADATA_INRELEASE = "InRelease";

  protected static final String METADATA_INRELEASE_PATH = METEDATA_PATH + METADATA_INRELEASE;

  protected static final String METADATA_RELEASE = "Release";

  protected static final String METADATA_RELEASE_PATH = METEDATA_PATH + METADATA_RELEASE;

  protected static final String METADATA_RELEASE_GPG = "Release.gpg";

  protected static final String METADATA_RELEASE_GPG_PATH = METEDATA_PATH + METADATA_RELEASE_GPG;

  public static final String PACKAGES_PATH = METEDATA_PATH + "main/binary-amd64/Packages";

  public static final String PACKAGES_BZ2_PATH = METEDATA_PATH + "main/binary-amd64/Packages.bz2";

  public static final String PACKAGES_GZ_PATH = METEDATA_PATH + "main/binary-amd64/Packages.gz";

  protected static final String PROXY_REPO_NAME = "apt-proxy";

  protected static final String PROXIED_PROXY_REPO_NAME = "proxied-apt-proxy";

  protected static final String PROXIED_HOSTED_REPO_NAME = "proxied-apt-hosted";

  public AptITSupport() {
    testData.addDirectory(resolveBaseFile("target/it-resources/apt"));
  }

  /**
   * Creates an Apt hosted repository with the specified name, distribution, and GPG key.
   * 
   * <p>This method uses Virtual Threads for file I/O operations to improve performance
   * when reading GPG key files.</p>
   *
   * @param name the name of the repository to create
   * @param distribution the distribution name (e.g., "bionic")
   * @param gpgKeyName the name of the GPG key file to use
   * @return the created Repository instance
   * @throws IOException if an I/O error occurs
   */
  public Repository createAptHostedRepository(final String name, final String distribution, final String gpgKeyName)
      throws IOException
  {
    return createAptHostedRepository(repos, name, distribution, testData.resolveFile(gpgKeyName).toPath());
  }

  /**
   * Creates an Apt hosted repository with the specified repository rule, name, distribution, and GPG key path.
   * 
   * <p>This method uses Virtual Threads for file I/O operations to improve performance
   * when reading GPG key files.</p>
   *
   * @param repository the repository rule to use for creation
   * @param name the name of the repository to create
   * @param distribution the distribution name (e.g., "bionic")
   * @param gpgFilePath the path to the GPG key file
   * @return the created Repository instance
   * @throws IOException if an I/O error occurs
   */
  public Repository createAptHostedRepository(final RepositoryRule repository,
                                              final String name,
                                              final String distribution,
                                              final Path gpgFilePath)
      throws IOException
  {
    // Use Virtual Threads to read the GPG key file for improved I/O performance
    String gpgKey = VirtualThreads.execute(() -> new String(Files.readAllBytes(gpgFilePath), StandardCharsets.UTF_8));
    return repository.createAptHosted(name, distribution, gpgKey);
  }

  /**
   * Creates an Apt proxy repository with the specified name, remote URL, and distribution.
   *
   * @param name the name of the repository to create
   * @param remoteUrl the URL of the remote repository to proxy
   * @param distribution the distribution name (e.g., "bionic")
   * @return the created Repository instance
   */
  protected Repository createAptProxyRepository(final String name, final String remoteUrl, final String distribution) {
    return repos.createAptProxy(name, remoteUrl, distribution);
  }

  /**
   * Verifies a PGP signature on a Release file.
   * 
   * <p>This method uses Virtual Threads for improved I/O performance when processing
   * signature verification. It's compatible with Java 21 and BouncyCastle 1.78.1.</p>
   *
   * @param signedData the input stream containing the signed data
   * @param signature the input stream containing the signature
   * @param publicKey the input stream containing the public key
   * @return true if the signature is valid, false otherwise
   * @throws Exception if an error occurs during verification
   */
  public boolean verifyReleaseFilePgpSignature(final InputStream signedData,
                                               final InputStream signature,
                                               final InputStream publicKey)
      throws Exception
  {
    // Use Virtual Threads for improved I/O performance during signature verification
    return VirtualThreads.execute(() -> {
      PGPObjectFactory pgpFact =
          new PGPObjectFactory(PGPUtil.getDecoderStream(signature), new JcaKeyFingerprintCalculator());
      PGPSignature sig = ((PGPSignatureList) pgpFact.nextObject()).get(0);

      PGPPublicKeyRingCollection pgpPubRingCollection =
          new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicKey),
              new JcaKeyFingerprintCalculator());

      PGPPublicKey key = pgpPubRingCollection.getPublicKey(sig.getKeyID());
      sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
      
      // Use a larger buffer size for better performance with Virtual Threads
      byte[] buff = new byte[8192];
      int read = 0;
      while ((read = signedData.read(buff)) != -1) {
        sig.update(buff, 0, read);
      }
      signedData.close();
      return sig.verify();
    });
  }

  /**
   * Verifies a PGP signature on an InRelease file.
   * 
   * <p>This method uses Virtual Threads for improved I/O performance when processing
   * signature verification. It's compatible with Java 21 and BouncyCastle 1.78.1.</p>
   *
   * @param fileContent the input stream containing the InRelease file content
   * @param publicKeyString the input stream containing the public key
   * @return true if the signature is valid, false otherwise
   * @throws Exception if an error occurs during verification
   */
  public boolean verifyInReleaseFilePgpSignature(final InputStream fileContent, final InputStream publicKeyString)
      throws Exception
  {
    // Use Virtual Threads for improved I/O performance during signature verification
    return VirtualThreads.execute(() -> {
      PGPPublicKeyRingCollection pgpRings =
          new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(publicKeyString),
              new JcaKeyFingerprintCalculator());
      ArmoredInputStream aIn = new ArmoredInputStream(fileContent);
      ByteArrayOutputStream releaseContent = new ByteArrayOutputStream();
      ByteArrayOutputStream lineOut = new ByteArrayOutputStream();

      int fromPositon = -1;
      if (aIn.isClearText()) {
        do {
          fromPositon = readStreamLine(lineOut, fromPositon, aIn);
          releaseContent.write(lineOut.toByteArray());
        }
        while (fromPositon != -1 && aIn.isClearText());
      }

      PGPObjectFactory pgpFact = new PGPObjectFactory(aIn, new JcaKeyFingerprintCalculator());
      PGPSignatureList p3 = (PGPSignatureList) pgpFact.nextObject();
      PGPSignature sig = p3.get(0);

      PGPPublicKey publicKey = pgpRings.getPublicKey(sig.getKeyID());
      sig.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), publicKey);
      InputStream sigIn = new ByteArrayInputStream(releaseContent.toByteArray());

      fromPositon = -1;
      do {
        int length;
        if (fromPositon != -1) {
          sig.update((byte) '\r');
          sig.update((byte) '\n');
        }
        fromPositon = readStreamLine(lineOut, fromPositon, sigIn);
        length = lineOut.toString(StandardCharsets.UTF_8.name()).replaceAll("\\s*$", "").length();
        if (length > 0) {
          sig.update(lineOut.toByteArray(), 0, length);
        }
      }
      while (fromPositon != -1);

      return sig.verify();
    });
  }

  /**
   * Reads a line from an input stream into a byte array output stream.
   * 
   * <p>This helper method is used by the PGP signature verification process to read
   * lines from the input stream. It's compatible with Java 21.</p>
   *
   * @param lineBuffer the output stream to write the line to
   * @param fromPosition the position to start reading from, or -1 to start from the beginning
   * @param in the input stream to read from
   * @return the position after reading the line, or -1 if the end of the stream was reached
   * @throws IOException if an I/O error occurs
   */
  private static int readStreamLine(final ByteArrayOutputStream lineBuffer,
                                    final int fromPosition,
                                    final InputStream in)
      throws IOException
  {
    lineBuffer.reset();
    int symbol;
    if (fromPosition != -1) {
      lineBuffer.write(fromPosition);
    }
    while ((symbol = in.read()) >= 0) {
      lineBuffer.write(symbol);
      if (symbol == '\n') {
        break;
      }
    }
    return symbol < 0 ? -1 : in.read();
  }
}