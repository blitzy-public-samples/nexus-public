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
package org.sonatype.nexus.common.hash;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA256;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA512;

/**
 * Tests for {@link Hashes}.
 */
@DisplayName("Hashes utility tests")
public class HashesTest
{
  private static final String DATA = "This is a test message for hashing!";

  private static final String MD5_HASH = "b4b91fa27dd64d4f14cd1e22e6a3c714";

  private static final String SHA1_HASH = "410fee1895a6af9449ae1647276259fd69a75b15";

  private static final String SHA512_HASH =
      "b90de0708205534bf3bc4e478c3718c7bf78b5ec60902dbbea234aadd748c004cdf94deda2034b0fa8bdc559ac59d6ac622211956bf782da33444d29e8d9f160";

  @Test
  @DisplayName("Hash with a single algorithm (MD5)")
  public void hashOne() throws Exception {
    HashCode hashCode = Hashes.hash(MD5, inputStream());

    assertThat(hashCode.toString(), is(MD5_HASH));
  }

  @Test
  @DisplayName("Hash with multiple algorithms (MD5, SHA1, SHA512)")
  public void hashThree() throws Exception {
    Map<HashAlgorithm, HashCode> hashes = Hashes.hash(ImmutableList.of(MD5, SHA1, SHA512), inputStream());

    assertThat(hashes.size(), is(3));
    assertThat(hashes.get(MD5).toString(), is(MD5_HASH));
    assertThat(hashes.get(SHA1).toString(), is(SHA1_HASH));
    assertThat(hashes.get(SHA512).toString(), is(SHA512_HASH));
  }

  @Test
  @DisplayName("Hash with empty algorithm list")
  public void hashZero() throws Exception {
    List<HashAlgorithm> zeroAlgorithms = ImmutableList.of();
    Map<HashAlgorithm, HashCode> hashes = Hashes.hash(zeroAlgorithms, inputStream());

    assertThat(hashes.size(), is(0));
  }

  private static InputStream inputStream() {
    return new ByteArrayInputStream(DATA.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("Hash stream with HashFunction directly")
  public void hashStreamWithFunction() throws Exception {
    byte[] bytes = DATA.getBytes(StandardCharsets.UTF_8);
    // Use SHA1 algorithm from our HashAlgorithm class instead of deprecated Guava Hashing.sha1()
    String expected = SHA1.function().hashBytes(bytes).toString();
    HashCode found = Hashes.hash(SHA1.function(), new ByteArrayInputStream(bytes));
    assertThat(found.toString(), is(expected));
  }

  @Test
  @DisplayName("Hash with SHA256 algorithm")
  public void hashWithSha256() throws Exception {
    byte[] bytes = DATA.getBytes(StandardCharsets.UTF_8);
    HashCode hashCode = Hashes.hash(SHA256, new ByteArrayInputStream(bytes));
    
    // SHA256 hash of the test data
    String expected = "e0c9035898dd52fc65c41454cec9c4d2611bfb37c7977d3491a3b8312f6a9575";
    assertThat(hashCode.toString(), is(expected));
  }
}
