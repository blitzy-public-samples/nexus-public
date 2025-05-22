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
package java21;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.ConstraintViolation;
import javax.validation.Valid;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Test class demonstrating Java 21 features with Bean Validation framework.
 * This includes record patterns, string templates, pattern matching for switch,
 * and virtual threads in the context of validation.
 */
public class Java21ValidationTest
    extends TestSupport
{
  private ValidatorFactory factory;

  @BeforeEach
  public void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
  }

  /**
   * Record for a person with validation constraints.
   */
  private record Person(
      @NotBlank String name,
      @Min(18) int age,
      @Valid Address address) {}

  /**
   * Record for an address with validation constraints.
   */
  private record Address(
      @NotBlank String street,
      @NotBlank String city,
      @Pattern(regexp = "^[0-9]{5}$") String zipCode) {}

  /**
   * Record for a user with validation constraints.
   */
  private record User(
      @NotBlank String username,
      @Size(min = 8) String password,
      @Valid List<@Valid Role> roles) {}

  /**
   * Record for a role with validation constraints.
   */
  private record Role(
      @NotBlank String name,
      @Valid List<@Valid Permission> permissions) {}

  /**
   * Record for a permission with validation constraints.
   */
  private record Permission(
      @NotBlank String resource,
      @NotNull AccessLevel accessLevel) {}

  /**
   * Enum for access levels.
   */
  private enum AccessLevel {
    READ, WRITE, ADMIN
  }

  /**
   * Test demonstrating record pattern matching with Bean Validation.
   * Uses Java 21's record patterns to destructure validated records.
   */
  @Test
  public void recordPatternMatchingWithValidation() {
    // Create a valid person
    Person validPerson = new Person("John Doe", 30, new Address("123 Main St", "Anytown", "12345"));
    
    // Validate the person
    Set<ConstraintViolation<Person>> violations = factory.getValidator().validate(validPerson);
    assertThat(violations, hasSize(0));
    
    // Use record pattern matching to destructure the person record
    if (validPerson instanceof Person(String name, int age, Address address)) {
      assertThat(name, equalTo("John Doe"));
      assertThat(age, equalTo(30));
      
      // Nested record pattern matching for the address
      if (address instanceof Address(String street, String city, String zipCode)) {
        assertThat(street, equalTo("123 Main St"));
        assertThat(city, equalTo("Anytown"));
        assertThat(zipCode, equalTo("12345"));
      }
    }
    
    // Create an invalid person
    Person invalidPerson = new Person("", 15, new Address("123 Main St", "Anytown", "invalid"));
    
    // Validate the invalid person
    violations = factory.getValidator().validate(invalidPerson);
    assertThat(violations, hasSize(3)); // name blank, age < 18, invalid zip code
    
    // Use record pattern matching with validation results
    for (ConstraintViolation<Person> violation : violations) {
      switch (violation.getPropertyPath().toString()) {
        case "name" -> assertThat(violation.getMessage(), equalTo("must not be blank"));
        case "age" -> assertThat(violation.getMessage(), equalTo("must be greater than or equal to 18"));
        case "address.zipCode" -> assertThat(violation.getMessage(), containsString("must match \"^[0-9]{5}$\""));
        default -> throw new AssertionError("Unexpected violation: " + violation.getPropertyPath());
      }
    }
  }

  /**
   * Test demonstrating nested record pattern matching with Bean Validation.
   * Uses Java 21's nested record patterns for complex object graphs.
   */
  @Test
  public void nestedRecordPatternMatchingWithValidation() {
    // Create a valid user with roles and permissions
    User user = new User("jdoe", "password123", List.of(
        new Role("admin", List.of(
            new Permission("users", AccessLevel.ADMIN),
            new Permission("reports", AccessLevel.READ)
        )),
        new Role("user", List.of(
            new Permission("profile", AccessLevel.WRITE)
        ))
    ));
    
    // Validate the user
    Set<ConstraintViolation<User>> violations = factory.getValidator().validate(user);
    assertThat(violations, hasSize(0));
    
    // Use nested record pattern matching to destructure the user record
    if (user instanceof User(String username, String password, List<Role> roles) && roles.size() >= 2) {
      assertThat(username, equalTo("jdoe"));
      assertThat(password, equalTo("password123"));
      
      // Get the first role using record pattern matching
      if (roles.get(0) instanceof Role(String roleName, List<Permission> permissions) && permissions.size() >= 2) {
        assertThat(roleName, equalTo("admin"));
        
        // Get the first permission using record pattern matching
        if (permissions.get(0) instanceof Permission(String resource, AccessLevel level)) {
          assertThat(resource, equalTo("users"));
          assertThat(level, equalTo(AccessLevel.ADMIN));
        }
      }
    }
    
    // Create an invalid user
    User invalidUser = new User("", "short", List.of(
        new Role("", List.of(
            new Permission("", null)
        ))
    ));
    
    // Validate the invalid user
    violations = factory.getValidator().validate(invalidUser);
    assertThat(violations, hasSize(4)); // username blank, password too short, role name blank, permission resource blank, permission level null
  }

  /**
   * Test demonstrating string templates with Bean Validation.
   * Uses Java 21's string templates for validation messages.
   */
  @Test
  public void stringTemplatesWithValidation() {
    // Create an invalid person
    Person invalidPerson = new Person("", 15, new Address("", "Anytown", "invalid"));
    
    // Validate the invalid person
    Set<ConstraintViolation<Person>> violations = factory.getValidator().validate(invalidPerson);
    assertThat(violations, hasSize(4)); // name blank, age < 18, street blank, invalid zip code
    
    // Use string templates to create error messages
    List<String> errorMessages = new ArrayList<>();
    for (ConstraintViolation<Person> violation : violations) {
      String path = violation.getPropertyPath().toString();
      String message = violation.getMessage();
      String template = STR."Validation error: \{path} \{message}";
      errorMessages.add(template);
    }
    
    // Verify error messages
    assertThat(errorMessages, hasSize(4));
    assertThat(errorMessages.stream().anyMatch(msg -> msg.contains("name must not be blank")), is(true));
    assertThat(errorMessages.stream().anyMatch(msg -> msg.contains("age must be greater than or equal to 18")), is(true));
    assertThat(errorMessages.stream().anyMatch(msg -> msg.contains("address.street must not be blank")), is(true));
    assertThat(errorMessages.stream().anyMatch(msg -> msg.contains("address.zipCode must match")), is(true));
    
    // Use string templates with conditional logic
    for (ConstraintViolation<Person> violation : violations) {
      String path = violation.getPropertyPath().toString();
      String message = violation.getMessage();
      String severity = path.contains("age") ? "ERROR" : "WARNING";
      String template = STR."[\{severity}] \{path}: \{message}";
      
      if (path.equals("age")) {
        assertThat(template, equalTo("[ERROR] age: must be greater than or equal to 18"));
      }
    }
  }

  /**
   * Test demonstrating pattern matching for switch with Bean Validation.
   * Uses Java 21's pattern matching for switch to handle different validation scenarios.
   */
  @Test
  public void patternMatchingForSwitchWithValidation() {
    // Create objects to validate
    Object[] objectsToValidate = {
        new Person("", 15, new Address("123 Main St", "Anytown", "12345")),
        new Address("", "Anytown", "invalid"),
        new User("", "short", List.of()),
        "Not a valid object",
        null
    };
    
    // Validate each object using pattern matching for switch
    for (Object obj : objectsToValidate) {
      String result = switch (obj) {
        case Person p -> {
          Set<ConstraintViolation<Person>> violations = factory.getValidator().validate(p);
          yield STR."Person validation failed with \{violations.size()} violations";
        }
        case Address a -> {
          Set<ConstraintViolation<Address>> violations = factory.getValidator().validate(a);
          yield STR."Address validation failed with \{violations.size()} violations";
        }
        case User u -> {
          Set<ConstraintViolation<User>> violations = factory.getValidator().validate(u);
          yield STR."User validation failed with \{violations.size()} violations";
        }
        case String s -> "Cannot validate a String";
        case null -> "Cannot validate null";
        default -> "Unknown object type";
      };
      
      // Verify results
      assertThat(result, switch (obj) {
        case Person p -> equalTo("Person validation failed with 2 violations");
        case Address a -> equalTo("Address validation failed with 2 violations");
        case User u -> equalTo("User validation failed with 2 violations");
        case String s -> equalTo("Cannot validate a String");
        case null -> equalTo("Cannot validate null");
        default -> equalTo("Unknown object type");
      });
    }
    
    // Test with a more complex object using nested pattern matching
    Object complexObj = Map.of(
        "user", new User("jdoe", "password123", List.of(
            new Role("admin", List.of(
                new Permission("users", AccessLevel.ADMIN)
            ))
        ))
    );
    
    String result = switch (complexObj) {
      case Map<?, ?> map when map.containsKey("user") && map.get("user") instanceof User u -> {
        Set<ConstraintViolation<User>> violations = factory.getValidator().validate(u);
        if (violations.isEmpty() && u instanceof User(String username, String password, var roles)) {
          yield STR."Valid user \{username} with \{roles.size()} roles";
        } else {
          yield STR."Invalid user with \{violations.size()} violations";
        }
      }
      default -> "Not a user map";
    };
    
    assertThat(result, equalTo("Valid user jdoe with 1 roles"));
  }

  /**
   * Test demonstrating virtual threads with Bean Validation.
   * Uses Java 21's virtual threads for concurrent validation.
   */
  @Test
  public void virtualThreadsWithValidation() throws Exception {
    // Create a list of objects to validate
    List<Person> peopleToValidate = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      // Mix of valid and invalid people
      if (i % 3 == 0) {
        peopleToValidate.add(new Person("", 15, new Address("", "Anytown", "invalid")));
      } else {
        peopleToValidate.add(new Person("Person " + i, 20 + i % 50, 
            new Address("Street " + i, "City " + i, "12345")));
      }
    }
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Track completion
      CountDownLatch latch = new CountDownLatch(peopleToValidate.size());
      AtomicInteger validCount = new AtomicInteger(0);
      AtomicInteger invalidCount = new AtomicInteger(0);
      
      // Submit validation tasks to virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (Person person : peopleToValidate) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Validate the person
            Set<ConstraintViolation<Person>> violations = factory.getValidator().validate(person);
            
            // Update counts
            if (violations.isEmpty()) {
              validCount.incrementAndGet();
            } else {
              invalidCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all validations to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertThat("All validation tasks should complete", completed, is(true));
      
      // Verify results
      assertThat(validCount.get() + invalidCount.get(), equalTo(peopleToValidate.size()));
      assertThat(validCount.get(), equalTo(667)); // 2/3 of 1000 are valid
      assertThat(invalidCount.get(), equalTo(333)); // 1/3 of 1000 are invalid
      
      // Ensure all futures completed without exceptions
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      allFutures.join(); // This would throw an exception if any future failed
    }
  }

  /**
   * Test demonstrating virtual threads with concurrent validation of complex object graphs.
   * Uses Java 21's virtual threads for high-throughput validation.
   */
  @Test
  public void virtualThreadsWithComplexValidation() throws Exception {
    // Create a large number of complex objects to validate
    int objectCount = 500;
    List<User> usersToValidate = new ArrayList<>();
    
    for (int i = 0; i < objectCount; i++) {
      // Create users with varying complexity
      List<Role> roles = new ArrayList<>();
      int roleCount = 1 + i % 5; // 1-5 roles per user
      
      for (int r = 0; r < roleCount; r++) {
        List<Permission> permissions = new ArrayList<>();
        int permCount = 1 + (i + r) % 10; // 1-10 permissions per role
        
        for (int p = 0; p < permCount; p++) {
          // Mix of valid and invalid permissions
          if ((i + r + p) % 7 == 0) {
            permissions.add(new Permission("", null)); // Invalid
          } else {
            permissions.add(new Permission(
                "resource-" + p, 
                AccessLevel.values()[(i + p) % AccessLevel.values().length]
            ));
          }
        }
        
        // Mix of valid and invalid roles
        if ((i + r) % 5 == 0) {
          roles.add(new Role("", permissions)); // Invalid
        } else {
          roles.add(new Role("role-" + r, permissions));
        }
      }
      
      // Mix of valid and invalid users
      if (i % 3 == 0) {
        usersToValidate.add(new User("", "short", roles)); // Invalid
      } else {
        usersToValidate.add(new User("user-" + i, "password" + i, roles));
      }
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Track validation results
      AtomicInteger totalViolations = new AtomicInteger(0);
      CountDownLatch latch = new CountDownLatch(objectCount);
      long startTime = System.currentTimeMillis();
      
      // Submit validation tasks
      for (User user : usersToValidate) {
        executor.submit(() -> {
          try {
            Set<ConstraintViolation<User>> violations = factory.getValidator().validate(user);
            totalViolations.addAndGet(violations.size());
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all validations to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      
      // Verify results
      assertThat("All validation tasks should complete", completed, is(true));
      assertThat("Should have detected violations", totalViolations.get(), greaterThan(0));
      
      // Log performance metrics
      log.info("Validated {} complex objects with {} total violations in {} ms", 
          objectCount, totalViolations.get(), duration);
      log.info("Average validation time: {} ms per object", (double) duration / objectCount);
    }
  }
}