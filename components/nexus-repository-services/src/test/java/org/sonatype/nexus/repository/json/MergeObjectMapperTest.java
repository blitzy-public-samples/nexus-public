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
package org.sonatype.nexus.repository.json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.io.InputStreamSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class MergeObjectMapperTest
    extends TestSupport
{
  private MergeObjectMapper underTest;

  @BeforeEach
  void setUp() {
    underTest = new MergeObjectMapper();
  }

  @Test
  void read_EmptyJson() throws IOException {
    InputStream inputStream = new ByteArrayInputStream("{}".getBytes());
    assertThat(underTest.read(inputStream).size(), is(0));
  }

  @Test
  void read_Json() throws IOException {
    try (InputStream inputStream = getClass().getResourceAsStream("merge-multi-depth-first.json")) {
      verifyReadFirstJson(underTest.read(inputStream));
    }
  }

  @Test
  void merge_Multiple_InputStreams_Into_Map() throws IOException {
    verifyMergeMultipleContents(this::mergeInputStreamsWhileStreaming);
  }

  @Test
  void merging_Multiple_InputStreams_WithMultiDepthJson() throws IOException {
    verifyMergingMultipleContentsWithMultiDepthJson(this::mergeInputStreamsWhileStreaming);
  }

  private void verifyMergeMultipleContents(Function<List<InputStreamSupplier>, NestedAttributesMap> function)
      throws IOException
  {
    try (InputStream recessive = getClass().getResourceAsStream("merge-streaming-payload-recessive.json");
         InputStream dominant = getClass().getResourceAsStream("merge-streaming-payload-dominant.json")) {
      verifyMergeMultipleContentsResult(function.apply(asList(() -> recessive, () -> dominant)));
    }
  }

  private void verifyReadFirstJson(final NestedAttributesMap result) {
    assertThat(result.size(), equalTo(9));
    assertThat(result.get("name"), equalTo("first"));

    List maintainers = (List) result.get("maintainers");
    assertThat(((Map) maintainers.get(0)).get("email"), equalTo("first@example.com"));
    assertThat(((Map) maintainers.get(0)).get("name"), equalTo("first"));
    assertThat(((Map) maintainers.get(1)).get("email"), equalTo("first2@example.com"));
    assertThat(((Map) maintainers.get(1)).get("name"), equalTo("first2"));

    List keywords = (List) result.get("keywords");
    assertThat(keywords.get(0), equalTo("array"));
    assertThat(keywords.get(1), equalTo("first"));

    assertThat(result.get("readme"), is(nullValue()));
    assertThat(result.get("multi-null-field"), is(nullValue()));

    NestedAttributesMap mulitDepth = result.child("multi-depth");
    NestedAttributesMap first = mulitDepth.child("first");

    assertThat(first.get("title"), equalTo("This is the first depth"));

    NestedAttributesMap reverseMulitDepth = result.child("reverse-multi-depth");
    first = reverseMulitDepth.child("first");
    NestedAttributesMap second = first.child("second");
    NestedAttributesMap third = second.child("third");

    assertThat(first.get("title"), equalTo("This is the first depth"));
    assertThat(second.get("title"), equalTo("This is the second depth"));
    assertThat(third.get("title"), equalTo("This is the third depth"));

    NestedAttributesMap mulitDepthMerge = result.child("multi-depth-merge");
    first = mulitDepthMerge.child("first");
    assertThat(first.get("title"), equalTo("This is the first depth"));

    NestedAttributesMap dependencies = result.child("dependencies");
    assertThat(dependencies.get("equire(\"orchestrator\")"), equalTo("*"));
    assertThat(dependencies.get("@types/node"), equalTo("*"));
    assertThat(dependencies.get("@types/orchestrator"), equalTo("*"));

    NestedAttributesMap funckyFieldName = dependencies.child("funcky(\"fieldname\"");
    NestedAttributesMap godeep = funckyFieldName.child("godeep");
    first = godeep.child("first");
    assertThat(first.get("title"), equalTo("This is the first depth"));
  }

  @SuppressWarnings("unchecked")
  private void verifyMergeMultipleContentsResult(final NestedAttributesMap result) {
    assertThat(result.backing(), hasEntry("_id", "id"));
    assertThat(result.backing(), hasEntry("_rev", "rev"));
    assertThat(result.backing(), hasEntry("name", "dominant"));
    assertThat(result.child("dist-tags").get("latest"), equalTo("1.0.0"));
    assertThat(result.child("versions").child("1.0").backing(), hasEntry("foo", "baz"));
    assertThat(result.child("versions").child("1.0").backing(), hasEntry("foo2", "bar2"));

    assertThat(result.backing(), hasEntry("notindominant", "always present"));
    assertThat(result.backing(), hasEntry("notinrecessive", "always present"));
    assertThat(result.child("not").child("in").backing(), hasEntry("dominant", "always present"));

    assertThat((List<String>) result.child("languages").child("en").get("alphabet"), contains("a", "b", "c"));

    assertThat((List<String>) result.get("alphabet"), contains("a", "b", "c"));
    assertThat((List<String>) result.get("recessive-numbers"), contains(1, 2, 3));
    assertThat((List<String>) result.get("dominant-numbers"), contains(9, 8, 7));

    Map<String, String> map = ((List<Map<String, String>>) result.get("maintainers")).get(0);
    assertThat(map, hasEntry("name", "j1"));
    assertThat(map, hasEntry("email", "j1@sonatype.com"));

    map = ((List<Map<String, String>>) result.get("maintainers")).get(1);
    assertThat(map, hasEntry("name", "j2"));
    assertThat(map, hasEntry("email", "j2@sonatype.com"));

    map = ((List<Map<String, String>>) result.get("recessive-maintainers")).get(0);
    assertThat(map, hasEntry("name", "jeremy"));
    assertThat(map, hasEntry("email", "jeremy@sonatype.com"));

    map = ((List<Map<String, String>>) result.get("dominant-maintainers")).get(0);
    assertThat(map, hasEntry("name", "nate"));
    assertThat(map, hasEntry("email", "nate@sonatype.com"));

    List<String> list = ((List<List<String>>) result.get("array-of-arrays")).get(0);
    assertThat(list, contains("ab", "cd", "ef"));

    list = ((List<List<String>>) result.get("array-of-arrays")).get(1);
    assertThat(list, contains("uv", "wx", "yz"));

    list = ((List<List<String>>) result.get("recessive-array-of-arrays")).get(0);
    assertThat(list, contains("gh", "ij", "kl"));

    list = ((List<List<String>>) result.get("recessive-array-of-arrays")).get(1);
    assertThat(list, contains("op", "qr", "st"));

    list = ((List<List<String>>) result.get("dominant-array-of-arrays")).get(0);
    assertThat(list, contains("ab", "cd", "ef"));

    list = ((List<List<String>>) result.get("dominant-array-of-arrays")).get(1);
    assertThat(list, contains("uv", "wx", "yz"));

    assertThat((List<Object>) result.get("null-array"), contains(nullValue(), nullValue(), nullValue()));

    assertThat((List<String>) result.get("alphabet"), contains("a", "b", "c"));

    List<Object> objects = (List<Object>) result.get("mixed-array");
    assertThat(objects.get(0), equalTo("circle"));
    assertThat(objects.get(1), equalTo(3.14));
    assertThat(((Map<String, String>) objects.get(2)), hasEntry("color", "blue"));
  }

  private void verifyMergingMultipleContentsWithMultiDepthJson(Function<List<InputStreamSupplier>, NestedAttributesMap> function)
      throws IOException
  {
    try (InputStream inputStream1 = getClass().getResourceAsStream("merge-multi-depth-first.json");
         InputStream inputStream2 = getClass().getResourceAsStream("merge-multi-depth-second.json");
         InputStream inputStream3 = getClass().getResourceAsStream("merge-multi-depth-third.json")) {
      verifyMergingMultipleContentsWithMultiDepthJsonResult(
          function.apply(asList(() -> inputStream1, () -> inputStream2, () -> inputStream3)));
    }
  }

  private void verifyMergingMultipleContentsWithMultiDepthJsonResult(final NestedAttributesMap result) {
    assertThat(result.size(), equalTo(9));
    assertThat(result.get("name"), equalTo("third"));

    List maintainers = (List) result.get("maintainers");
    assertThat(((Map) maintainers.get(0)).get("email"), equalTo("third@example.com"));
    assertThat(((Map) maintainers.get(0)).get("name"), equalTo("third"));

    List keywords = (List) result.get("keywords");
    assertThat(keywords.get(0), equalTo("array"));
    assertThat(keywords.get(1), equalTo("third"));

    assertThat(result.get("readme"), is(nullValue()));
    assertThat(result.get("multi-null-field"), equalTo("not so null in third"));

    NestedAttributesMap mulitDepth = result.child("multi-depth");
    NestedAttributesMap first = mulitDepth.child("first");
    NestedAttributesMap second = first.child("second");
    NestedAttributesMap third = second.child("third");

    assertThat(first.get("title"), equalTo("This is the first depth"));
    assertThat(second.get("title"), equalTo("This is the second depth"));
    assertThat(third.get("title"), equalTo("This is the third depth"));

    NestedAttributesMap reverseMulitDepth = result.child("reverse-multi-depth");
    first = reverseMulitDepth.child("first");
    assertThat(first.get("title"), equalTo("This is the first depth"));

    NestedAttributesMap mulitDepthMerge = result.child("multi-depth-merge");
    first = mulitDepthMerge.child("first");

    assertThat(first.get("summary"), equalTo("We have here the first depth from the third response"));

    NestedAttributesMap dependencies = result.child("dependencies");
    assertThat(dependencies.get("equire(\"orchestrator\")"), equalTo("*"));
    assertThat(dependencies.get("@types/node"), equalTo("*"));
    assertThat(dependencies.get("@types/orchestrator"), equalTo("*"));

    NestedAttributesMap funckyFieldName = dependencies.child("funcky(\"fieldname\"");
    assertThat(funckyFieldName.get("test"), equalTo("value"));
  }

  private NestedAttributesMap mergeInputStreamsWhileStreaming(final List<InputStreamSupplier> inputStreams) {
    try {
      return underTest.merge(inputStreams);
    }
    catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
  
  /**
   * Test case that demonstrates the use of record patterns with JSON processing.
   * This test validates that record patterns can be used to extract and process JSON data
   * in a more concise and type-safe manner.
   */
  @Test
  void testRecordPatternWithJsonProcessing() throws IOException {
    // Define records to represent JSON structure
    record Maintainer(String name, String email) {}
    record JsonPackage(String name, List<Maintainer> maintainers) {}
    
    // Create test JSON
    String json = "{\"name\":\"test-package\",\"maintainers\":[{\"name\":\"developer1\",\"email\":\"dev1@example.com\"}]}"; 
    InputStream inputStream = new ByteArrayInputStream(json.getBytes());
    
    // Parse JSON
    NestedAttributesMap result = underTest.read(inputStream);
    
    // Convert to our record structure
    String packageName = (String) result.get("name");
    List<Map<String, String>> maintainersList = (List<Map<String, String>>) result.get("maintainers");
    
    // Use record pattern matching to process the data
    if (maintainersList != null && !maintainersList.isEmpty()) {
      Map<String, String> firstMaintainer = maintainersList.get(0);
      Maintainer maintainer = new Maintainer(firstMaintainer.get("name"), firstMaintainer.get("email"));
      JsonPackage pkg = new JsonPackage(packageName, List.of(maintainer));
      
      // Pattern matching with record patterns
      if (pkg instanceof JsonPackage(String name, var maintainers) && 
          !maintainers.isEmpty() && 
          maintainers.get(0) instanceof Maintainer(String mName, String mEmail)) {
        // Assertions using the extracted fields
        assertThat(name, equalTo("test-package"));
        assertThat(mName, equalTo("developer1"));
        assertThat(mEmail, equalTo("dev1@example.com"));
      }
    }
  }
  
  /**
   * Test case that demonstrates concurrent JSON merging operations using virtual threads.
   * This test validates that virtual threads can be used to efficiently process multiple
   * JSON merging operations concurrently.
   */
  @Test
  void testConcurrentJsonMergingWithVirtualThreads() throws Exception {
    // Create test JSON files
    String baseJson = "{\"name\":\"base\",\"version\":\"1.0.0\"}"; 
    String updateJson = "{\"description\":\"Test package\",\"version\":\"1.0.1\"}"; 
    
    // Create input stream suppliers
    InputStreamSupplier baseSupplier = () -> new ByteArrayInputStream(baseJson.getBytes());
    InputStreamSupplier updateSupplier = () -> new ByteArrayInputStream(updateJson.getBytes());
    
    // Number of concurrent operations to perform
    int concurrentOperations = 100;
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to merge JSON using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[concurrentOperations];
      
      for (int i = 0; i < concurrentOperations; i++) {
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Perform JSON merge operation
            NestedAttributesMap result = underTest.merge(asList(baseSupplier, updateSupplier));
            
            // Verify merge was successful
            if ("base".equals(result.get("name")) && 
                "1.0.1".equals(result.get("version")) && 
                "Test package".equals(result.get("description"))) {
              successCount.incrementAndGet();
            }
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }, executor);
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures).join();
      
      // Verify all operations were successful
      assertThat(successCount.get(), equalTo(concurrentOperations));
    }
  }
}