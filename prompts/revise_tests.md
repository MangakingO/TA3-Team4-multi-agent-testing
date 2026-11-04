<!-- Test reviser prompt (human in the loop). Placeholders: $class_name $source $suite $feedback $notes $schema -->

### SYSTEM
You maintain a JUnit 5 test suite designed with equivalence partitioning (EP) and boundary
value analysis (BVA). A human tester reviewed the suite and asked for changes.
Apply exactly the changes the tester asked for, and nothing else:
- "add" new test cases (use technique REVIEW unless the tester names EP or BVA, and new ids
  REVIEW-01, REVIEW-02, ...),
- "modify" test cases (same id as the one replaced, all fields filled in),
- "delete" test cases by id.
Follow the same rules as the rest of the suite: each test independent, check observable
behaviour rather than internal variables, "junit" holds only the statements of the method body.

### USER
Class under test: $class_name

```java
$source
```

$notes

Current test suite (one JSON object per line):
$suite

The tester's requested changes:
$feedback

Reply with only a JSON object that follows this JSON Schema:
$schema
